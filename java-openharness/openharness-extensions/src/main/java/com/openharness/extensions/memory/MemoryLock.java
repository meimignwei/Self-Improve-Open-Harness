package com.openharness.extensions.memory;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exclusive file locking for memory mutation operations.
 * Java equivalent of Python utils/file_lock.py — uses FileChannel.lock()
 * which delegates to fcntl/flock on POSIX and LockFile on Windows.
 */
public final class MemoryLock implements AutoCloseable {

    private final Path lockPath;
    private RandomAccessFile raf;
    private FileChannel channel;
    private FileLock lock;

    private MemoryLock(Path lockPath) {
        this.lockPath = lockPath;
    }

    /**
     * Acquire an exclusive file lock on the .memory.lock file.
     * Returns an AutoCloseable; use try-with-resources.
     */
    public static MemoryLock acquire(Path lockPath) {
        MemoryLock ml = new MemoryLock(lockPath);
        try {
            Files.createDirectories(lockPath.getParent());
            // Create lock file if it doesn't exist
            if (!Files.exists(lockPath)) {
                Files.createFile(lockPath);
            }
            ml.raf = new RandomAccessFile(lockPath.toFile(), "rw");
            ml.channel = ml.raf.getChannel();
            ml.lock = ml.channel.lock(); // exclusive lock, blocks until acquired
        } catch (Exception e) {
            ml.closeQuietly();
            throw new RuntimeException("Failed to acquire memory lock: " + lockPath, e);
        }
        return ml;
    }

    @Override
    public void close() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (raf != null) {
                raf.close();
            }
        } catch (Exception ignored) {
        }
    }

    private void closeQuietly() {
        try { close(); } catch (Exception ignored) { }
    }
}
