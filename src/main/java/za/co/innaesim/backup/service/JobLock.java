package za.co.innaesim.backup.service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import za.co.innaesim.backup.config.AppPaths;

/** Stops one job running twice at once, including a manual run during a daemon run. The OS releases it on exit. */
final class JobLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private JobLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static JobLock acquire(String job) throws IOException {
        Files.createDirectories(AppPaths.locksDir());
        Path file = AppPaths.locksDir().resolve(job + ".lock");
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            // Held by this same process; treated like any other holder below.
        }
        if (lock == null) {
            channel.close();
            throw new IllegalStateException("Job '" + job + "' is already running");
        }
        return new JobLock(channel, lock);
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
