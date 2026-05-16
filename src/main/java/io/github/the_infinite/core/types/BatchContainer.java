package io.github.the_infinite.core.types;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@SuppressWarnings("unused")
public class BatchContainer<T> {
    private List<T> activeBatch;
    private final int batchSize;
    private final Queue<List<T>> completeBatches;
    private final Lock lock;
    private final AtomicInteger processedEvents;

    public BatchContainer(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be greater than 0");
        }
        this.batchSize = batchSize;
        this.completeBatches = new ConcurrentLinkedQueue<>();
        this.activeBatch = new ArrayList<>(batchSize);
        this.lock = new ReentrantLock();
        this.processedEvents = new AtomicInteger(0);
    }

    /**
     * Adds an item to the current active batch.
     * If the batch becomes full, it is moved to the complete batches queue.
     * Use this method for thread-safe addition of items.
     *
     * @param item The item to add
     */
    public void add(T item) {
        lock.lock();
        try {
            activeBatch.add(item);
            processedEvents.getAndIncrement();
            if (activeBatch.size() >= batchSize) {
                completeBatches.offer(Collections.unmodifiableList(activeBatch));
                activeBatch = new ArrayList<>(batchSize);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the next complete batch from the queue.
     * Returns null if no complete batches are available.
     *
     * @return An unmodifiable list representing a batch of items, or null if empty.
     */
    public Optional<List<T>> poll() {
        return Optional.ofNullable(completeBatches.poll());
    }

    /**
     * Retrieves the total number of items currently in the active batch and all
     * complete batches.
     */
    public int size() {
        lock.lock();
        try {
            return activeBatch.size() + completeBatches.stream().mapToInt(List::size).sum();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Forces the current active batch to be marked as complete, even if not full,
     * so it can be retrieved via poll().
     */
    public void flush() {
        lock.lock();
        try {
            if (!activeBatch.isEmpty()) {
                completeBatches.offer(Collections.unmodifiableList(activeBatch));
                activeBatch = new ArrayList<>(batchSize);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the total number of elements that have been processed by this batch container,
     * including those in the active batch, all complete batches, and all processed
     * batches.
     */
    public long getProcessedEvents() {
        return processedEvents.get();
    }

    /**
     * Checks if there are any complete batches ready to be processed.
     *
     * @return true if there are complete batches, false otherwise.
     */
    public boolean hasReadyBatches() {
        return !completeBatches.isEmpty();
    }

    /**
     * Returns the configured batch size.
     *
     * @return The maximum size of each batch.
     */
    public int getBatchSize() {
        return batchSize;
    }
}
