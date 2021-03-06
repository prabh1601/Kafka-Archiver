package com.prabh.Archiver;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.prabh.Utils.CompressionType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class WriteService {
    private final Logger logger = LoggerFactory.getLogger(WriteService.class);
    private final ExecutorService taskExecutor;
    private final CompressionType compressionType;
    private final List<ConcurrentHashMap<TopicPartition, WritingTask>> activeTasks;
    private final ConcurrentHashMap<TopicPartition, TopicPartitionWriter> activeBatches = new ConcurrentHashMap<>();
    private final UploadService uploadService;
//    private final TPSCalculator tps =  new TPSCalculator().start(30L, TimeUnit.SECONDS, new TPSCalculator.AbstractTPSCallback() {
//        @Override
//        public void tpsStat(TPSCalculator.ProgressStat stat) {
//            logger.error("stats: " + stat.toString());
//        }
//    });

    public WriteService(int noOfConsumers, int taskPoolSize, CompressionType _compressionType, UploadService _uploadService) {
        this.uploadService = _uploadService;
        this.compressionType = _compressionType;
        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder().setNameFormat("WRITER-%d").build();
        this.taskExecutor = Executors.newFixedThreadPool(taskPoolSize, namedThreadFactory);
        activeTasks = new ArrayList<>(noOfConsumers);
        for (int i = 0; i < noOfConsumers; i++) {
            activeTasks.add(new ConcurrentHashMap<>());
        }
    }

    public void submit(int consumer, TopicPartition partition, List<ConsumerRecord<String, String>> records) {
        WritingTask t = new WritingTask(records, partition);
        taskExecutor.submit(t);
        activeTasks.get(consumer).put(partition, t);
    }

    public Map<TopicPartition, OffsetAndMetadata> checkActiveTasks(int consumer) {
        Map<TopicPartition, OffsetAndMetadata> donePartitions = new HashMap<>();
        List<TopicPartition> tasksToRemove = new ArrayList<>();
        activeTasks.get(consumer).forEach((currentPartition, task) -> {
            long offset = task.getCurrentOffset();
            if (task.isFinished() && offset > 0) {
                donePartitions.put(currentPartition, new OffsetAndMetadata(offset));
                tasksToRemove.add(currentPartition);
            }
        });

        tasksToRemove.forEach(activeTasks.get(consumer)::remove);
        return donePartitions;
    }

    public Map<TopicPartition, OffsetAndMetadata> handleRevokedPartitionTasks(int consumer, Collection<TopicPartition> partitions) {

        // 1. Fetch the tasks of revoked partitions and stop them
        Map<TopicPartition, WritingTask> revokedTasks = new HashMap<>();
        partitions.forEach(currentPartition -> {
            WritingTask task = activeTasks.get(consumer).remove(currentPartition);
            if (task != null) {
                task.stop();
                revokedTasks.put(currentPartition, task);
            }
        });

        // 2. Wait for revoked tasks to complete processing current record and fetch offsets
        Map<TopicPartition, OffsetAndMetadata> revokedPartitionOffsets = new HashMap<>();
        revokedTasks.forEach((currentPartition, task) -> {
            long offset = task.waitForCompletion();
            if (offset > 0) {
                revokedPartitionOffsets.put(currentPartition, new OffsetAndMetadata(offset));
            }
        });

        return revokedPartitionOffsets;
    }

    public void shutdown() {
        taskExecutor.shutdown();
        try {
            taskExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }

        Set<TopicPartition> pendingBatches = activeBatches.keySet();
        pendingBatches.forEach(this::commitBatch);
        logger.warn("Writing Client Shutdown complete");
    }

    boolean readyForCommit(TopicPartition partition) {
        if (!activeBatches.containsKey(partition)) {
            return false;
        }

        return activeBatches.get(partition).readyForCommit();
    }

    public void addToBuffer(TopicPartition partition, List<ConsumerRecord<String, String>> records) {
        activeBatches.get(partition).addToBuffer(records);
    }

    public void initializeNewBatch(TopicPartition partition, ConsumerRecord<String, String> record) {
        assert activeBatches.containsKey(partition);
        TopicPartitionWriter batch = new TopicPartitionWriter(record, compressionType);
        activeBatches.put(partition, batch);
    }

    public long getRemainingSpace(TopicPartition partition) {
        assert activeBatches.containsKey(partition);
        return activeBatches.get(partition).remainingSpace();
    }

    public void commitBatch(TopicPartition partition) {
        TopicPartitionWriter batch = activeBatches.get(partition);
        uploadService.submit(new File(batch.getFilePath()), batch.getKey());
        activeBatches.remove(partition);
    }

    private class WritingTask implements Runnable {
        private final Logger logger = LoggerFactory.getLogger(WritingTask.class.getName());
        private final List<ConsumerRecord<String, String>> records;
        private final TopicPartition partition;
        private volatile boolean stopped = false;
        private volatile boolean started = false;
        private volatile boolean finished = false;
        private final AtomicLong currentOffset = new AtomicLong();
        private final ReentrantLock startStopLock = new ReentrantLock();
        private final CompletableFuture<Long> completion = new CompletableFuture<>();

        public WritingTask(List<ConsumerRecord<String, String>> _records, TopicPartition _partition) {
            this.records = _records;
            this.partition = _partition;
        }

        @Override
        public void run() {
            startStopLock.lock();
            if (stopped) return; // This happens when the task is still in executor queue
            started = true; // Task is started by executor thread pool
            startStopLock.unlock();

            int n = records.size();
            for (int i = 0; i < n; ) {
                if (stopped) break;
                if (readyForCommit(partition)) {
                    commitBatch(partition);
                }

                if (!activeBatches.containsKey(partition)) {
                    initializeNewBatch(partition, records.get(i));
                }

                List<ConsumerRecord<String, String>> batchedRecords = new ArrayList<>(n - i);
                long remainingSpace = getRemainingSpace(partition);
                while (i < n && remainingSpace > 0) {
                    ConsumerRecord<String, String> record = records.get(i);
                    batchedRecords.add(record);
                    remainingSpace -= (record.serializedValueSize() + 1);
                    i++;
                    currentOffset.set(record.offset() + 1);
                }
                addToBuffer(partition, batchedRecords);
            }
            finished = true;
            completion.complete(currentOffset.get());
        }

        public long getCurrentOffset() {
            return currentOffset.get();
        }

        public void stop() {
            startStopLock.lock();
            this.stopped = true;
            if (!started) {
                finished = true;
                completion.complete(currentOffset.get());
            }
            startStopLock.unlock();
        }

        public long waitForCompletion() {
            try {
                return completion.get();
            } catch (InterruptedException | ExecutionException e) {
                return -1;
            }
        }

        public boolean isFinished() {
            return finished;
        }
    }
}
