package com.storynpcs.yaml;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Lifecycle-owned in-process mutexes paired with the on-disk definition locks. */
public final class DefinitionWriteCoordinator {
    private static final long LOCAL_LOCK_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);

    private final ConcurrentHashMap<Path, ReentrantLock> localLocksByDirectory = new ConcurrentHashMap<>();

    DefinitionWriteLock acquire(Path definitionDirectory) throws IOException {
        Path realDirectory = definitionDirectory.toRealPath();
        ReentrantLock localLock = localLocksByDirectory.computeIfAbsent(realDirectory, ignored -> new ReentrantLock());
        try {
            if (!localLock.tryLock(LOCAL_LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IOException("Timed out waiting for in-process definition lock: " + realDirectory);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for in-process definition lock: " + realDirectory, e);
        }

        try {
            return DefinitionWriteLock.acquire(realDirectory, localLock);
        } catch (IOException | RuntimeException failure) {
            localLock.unlock();
            throw failure;
        }
    }
}
