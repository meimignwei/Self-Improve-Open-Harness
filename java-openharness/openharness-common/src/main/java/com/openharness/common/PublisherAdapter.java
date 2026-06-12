package com.openharness.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Adapts Flow.Publisher to Stream/List for synchronous consumption and testing.
 */
public final class PublisherAdapter {

    private PublisherAdapter() {}

    /**
     * Collects all events from a Flow.Publisher into a Stream.
     * Uses a BlockingQueue + Spliterator for backpressure-safe collection.
     */
    public static <T> Stream<T> toStream(Flow.Publisher<T> publisher) {
        BlockingQueue<T> queue = new LinkedBlockingQueue<>();
        List<T> items = new ArrayList<>();

        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(T item) {
                queue.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                queue.add(null); // sentinel for completion
            }

            @Override
            public void onComplete() {
                queue.add(null); // sentinel for completion
            }
        });

        return StreamSupport.stream(
                new QueueSpliterator<>(queue), false);
    }

    /**
     * Collects all events into a List. Blocks until the publisher completes.
     */
    public static <T> List<T> toList(Flow.Publisher<T> publisher) {
        return toStream(publisher).toList();
    }

    private record QueueSpliterator<T>(BlockingQueue<T> queue)
            implements java.util.Spliterator<T> {

        @Override
        public boolean tryAdvance(java.util.function.Consumer<? super T> action) {
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
        public java.util.Spliterator<T> trySplit() {
            return null; // cannot split
        }

        @Override
        public long estimateSize() {
            return Long.MAX_VALUE; // unknown size
        }

        @Override
        public int characteristics() {
            return ORDERED | NONNULL;
        }
    }
}
