package com.openharness.extensions.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * PID-based file lock to prevent concurrent write conflicts.
 * Java equivalent of Python utils/file_lock.py.
 */
public class FileLock implements AutoCloseable {

    private final FileChannel channel;
    private final java.nio.channels.FileLock lock;
    private final Path lockFile;

    private FileLock(FileChannel channel, java.nio.channels.FileLock lock, Path lockFile) {
        this.channel = channel;
        this.lock = lock;
        this.lockFile = lockFile;
    }

    public static FileLock acquire(Path lockFile) throws IOException {
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        java.nio.channels.FileLock lock = channel.tryLock();
        if (lock == null) {
            channel.close();
            throw new LockAcquisitionException(lockFile);
        }
        channel.write(ByteBuffer.wrap((ProcessHandle.current().pid() + "\n").getBytes()));
        channel.force(true);
        return new FileLock(channel, lock, lockFile);
    }

    @Override
    public void close() throws IOException {
        lock.release();
        channel.close();
        Files.deleteIfExists(lockFile);
    }

    public static class LockAcquisitionException extends IOException {
        public LockAcquisitionException(Path lockFile) {
            super("Failed to acquire file lock: " + lockFile);
        }
    }
}
