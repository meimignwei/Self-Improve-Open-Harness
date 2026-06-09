package io.openharness.core.persistence;

import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 异步持久化写入器。非阻塞 enqueue → 单消费者线程批量 drainTo → MyBatis BATCH 提交。
 * 队列满时丢弃并 WARN，不阻塞 Agent 主循环。
 */
public class AsyncPersistenceWriter {

    private static final Logger log = LoggerFactory.getLogger(AsyncPersistenceWriter.class);
    private static final int QUEUE_CAPACITY = 10000;
    private static final int BATCH_SIZE = 50;

    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final SqlSessionFactory sessionFactory;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread consumer;

    public AsyncPersistenceWriter(SqlSessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
        this.consumer = new Thread(this::consumeLoop, "oh-persistence-writer");
        this.consumer.setDaemon(true);
        this.consumer.start();
        log.info("AsyncPersistenceWriter started, capacity={}", QUEUE_CAPACITY);
    }

    /** 非阻塞入队。队列满返回 false，打印 WARN。 */
    public boolean enqueue(Runnable task) {
        boolean offered = queue.offer(task);
        if (!offered) {
            log.warn("Persistence queue full ({}), dropping task", QUEUE_CAPACITY);
        }
        return offered;
    }

    public int getQueueDepth() {
        return queue.size();
    }

    public void shutdown() {
        running.set(false);
        consumer.interrupt();
        flushAll();
        log.info("AsyncPersistenceWriter shut down");
    }

    private void flushAll() {
        List<Runnable> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        for (Runnable task : remaining) {
            try { task.run(); } catch (Exception e) { log.error("Flush task failed", e); }
        }
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                Runnable task = queue.take();
                List<Runnable> batch = new ArrayList<>(BATCH_SIZE);
                batch.add(task);
                queue.drainTo(batch, BATCH_SIZE - 1);

                try (var session = sessionFactory.openSession(org.apache.ibatis.session.ExecutorType.BATCH)) {
                    for (Runnable r : batch) { r.run(); }
                    session.commit();
                } catch (Exception e) {
                    log.error("Batch commit failed, {} tasks lost", batch.size(), e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
