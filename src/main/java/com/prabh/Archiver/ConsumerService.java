package com.prabh.Archiver;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConsumerService {
    private final Logger logger = LoggerFactory.getLogger(ConsumerService.class);
    private final List<ConsumerWorker> consumers;
    private final ExecutorService workers;
    private final WriteService writer;
    private final CountDownLatch runningStatus;
    private final int noOfConsumers;
    private final String groupName;
    private final String serverId;
    private final List<String> subscribedTopics;

    public ConsumerService(WriteService _writer, int _noOfConsumers, String _groupName, String _serverId, List<String> topics) {
        this.writer = _writer;
        this.groupName = _groupName;
        this.serverId = _serverId;
        this.noOfConsumers = _noOfConsumers;

        ThreadFactory tf = new ThreadFactoryBuilder().setNameFormat("CONSUMER-WORKER-%d").build();
        this.workers = Executors.newFixedThreadPool(noOfConsumers, tf);
        this.runningStatus = new CountDownLatch(this.noOfConsumers);
        this.consumers = new ArrayList<>(_noOfConsumers);
        this.subscribedTopics = topics;
    }

    public void start() {
        for (int i = 0; i < noOfConsumers; i++) {
            ConsumerWorker c = new ConsumerWorker(i);
            workers.execute(c);
            synchronized (consumers) {
                consumers.add(c);
            }
        }
    }

    public void shutdown() {
        synchronized (consumers) {
            consumers.forEach(ConsumerWorker::stopConsumer);
            consumers.clear();
        }
        workers.shutdown();
        try {
            runningStatus.await();
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
        logger.warn("Consumer Service Shutdown Complete");
    }

    private class ConsumerWorker extends Thread implements ConsumerRebalanceListener {
        private final Logger logger = LoggerFactory.getLogger(ConsumerWorker.class.getName());
        private final KafkaConsumer<String, String> consumer;
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final Map<TopicPartition, OffsetAndMetadata> pendingOffsets = new HashMap<>();
        private final int consumerNo;
        private long lastCommitTime = System.currentTimeMillis();

        public ConsumerWorker(int consumerNumber) {
            this.consumerNo = consumerNumber;
            this.consumer = createKafkaConsumer();
        }

        public KafkaConsumer<String, String> createKafkaConsumer() {
            Properties consumerProperties = new Properties();
            consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, serverId);
            consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, groupName);
            consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
//            consumerProperties.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 50 * 1024 * 1024);
            consumerProperties.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 2 * 1024 * 1024);
            consumerProperties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Integer.MAX_VALUE);
            return new KafkaConsumer<>(consumerProperties);
        }

        @Override
        public void run() {
            try {
                logger.warn("{} Started", Thread.currentThread().getName());

                consumer.subscribe(subscribedTopics, this);
                while (!stopped.get()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(2000));
                    System.out.println("done");
                    if (!records.isEmpty()) {
                        log(records); // makes logs too messy : rather get some better method
                        handleFetchedRecords(records);
                    }
                    checkActiveTasks();
                    commitOffsets();
                }
            } catch (WakeupException e) {
                if (!stopped.get()) {
                    throw e;
                }
            } finally {
                consumer.close();
                runningStatus.countDown();
                logger.warn("{} Shutdown Successfully", Thread.currentThread().getName());
            }
        }

        public void handleFetchedRecords(ConsumerRecords<String, String> records) {
            List<TopicPartition> partitionsToPause = new ArrayList<>();
            records.partitions().forEach(currentPartition -> {
                List<ConsumerRecord<String, String>> partitionRecords = records.records(currentPartition);
                writer.submit(consumerNo, currentPartition, partitionRecords);
                partitionsToPause.add(currentPartition);
            });

            consumer.pause(partitionsToPause);
        }

        public void checkActiveTasks() {
            List<TopicPartition> partitionsToResume = new ArrayList<>();
            Map<TopicPartition, OffsetAndMetadata> doneTasks = writer.checkActiveTasks(consumerNo);
            doneTasks.forEach((currentPartition, offsets) -> {
                partitionsToResume.add(currentPartition);
                pendingOffsets.put(currentPartition, offsets);
            });
            if (!partitionsToResume.isEmpty()) {
                consumer.resume(partitionsToResume);
            }
        }

        public void commitOffsets() {
            try {
                long currentTimeInMillis = System.currentTimeMillis();
                if (currentTimeInMillis - lastCommitTime > 5000) {
                    if (!pendingOffsets.isEmpty()) {
                        consumer.commitSync(pendingOffsets);
                        pendingOffsets.clear();
                    }
                    lastCommitTime = currentTimeInMillis;
                }
            } catch (Exception e) {
                logger.error("Failed to commit offsets during routine offset commit");
            }
        }

        public void log(ConsumerRecords<String, String> records) {
            logger.info("Fetched {} records constituting of {}", records.count(), records.partitions());
        }

        public void stopConsumer() {
            logger.warn("Stopping");
            stopped.set(true);
            consumer.wakeup();
        }

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {

            Map<TopicPartition, OffsetAndMetadata> revokedPartitionOffsets = writer.handleRevokedPartitionTasks(consumerNo, partitions);
            pendingOffsets.putAll(revokedPartitionOffsets);

            // 3. Get the offsets of revoked partitions
            Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new HashMap<>();
            partitions.forEach(currentPartition -> {
                OffsetAndMetadata offset = pendingOffsets.remove(currentPartition);
                if (offset != null) {
                    offsetsToCommit.put(currentPartition, offset);
                }
            });

            // 4. Commit offsets of revoked partitions
            try {
                consumer.commitSync(offsetsToCommit);
            } catch (Exception e) {
                logger.error("Failed to commit offset during re-balance");
            }
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            consumer.resume(partitions);
        }
    }
}