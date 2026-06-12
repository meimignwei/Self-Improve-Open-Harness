package com.openharness.common;

import java.util.Spliterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A Spliterator that reads from a BlockingQueue, treating null as sentinel (end of stream).
 * Used for adapting SSE event queues to Java Stream API.
 */
public final class StreamingSpliterator<T> implements Spliterator<T> {

    private final BlockingQueue<T> queue;

    public StreamingSpliterator(BlockingQueue<T> queue) {
        this.queue = queue;
    }

    @Override
    public boolean tryAdvance(Consumer<? super T> action) {
        try {
            T item = queue.poll(1, TimeUnit.HOURS);
            if (item == null) {
                return false; // sentinel: stream complete
            }
            action.accept(item);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public Spliterator<T> trySplit() {
        return null; // cannot split a live stream
    }

    @Override
    public long estimateSize() {
        return Long.MAX_VALUE; // unknown size for live streams
    }

    @Override
    public int characteristics() {
        return ORDERED | NONNULL;
    }
}
