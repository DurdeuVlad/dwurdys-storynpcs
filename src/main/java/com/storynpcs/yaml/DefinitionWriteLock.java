package com.storynpcs.yaml;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Lock token pairing an application-local mutex with the cooperating-process file lock. */
final class DefinitionWriteLock implements AutoCloseable {
    private static final String LOCK_FILE = ".storynpcs-definition-writer.lock";
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long RETRY_MILLIS = 10;

    private final FileChannel channel;
    private final FileLock lock;
    private final ReentrantLock localLock;

    private DefinitionWriteLock(FileChannel channel, FileLock lock, ReentrantLock localLock) {
        this.channel = channel;
        this.lock = lock;
        this.localLock = localLock;
    }

    static DefinitionWriteLock acquire(Path definitionDirectory, ReentrantLock localLock) throws IOException {
        Path lockPath = definitionDirectory.resolve(LOCK_FILE);
        if (Files.isSymbolicLink(lockPath)) {
            throw new IOException("Definition writer lock file must not be a symbolic link: " + lockPath);
        }

        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            return new DefinitionWriteLock(channel, acquireChannelLock(channel), localLock);
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static FileLock acquireChannelLock(FileChannel channel) throws IOException {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException ignored) {
                // Another writer in this JVM owns the same directory lock; retry until it releases it.
            }

            try {
                Thread.sleep(RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for definition writer lock", e);
            }
        }
        throw new IOException("Timed out waiting for definition writer lock: " + channel);
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            try {
                lock.release();
            } catch (IOException e) {
                failure = e;
            }
        } finally {
            try {
                channel.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            } finally {
                localLock.unlock();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
