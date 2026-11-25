package za.co.innaesim.backup.schedule;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import za.co.innaesim.backup.config.AppPaths;
import za.co.innaesim.backup.model.BackupSettings;
import za.co.innaesim.backup.model.JobConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs scheduled jobs. Config is re-read each minute so 'schedule add' takes effect without a
 * restart; an invalid config is logged and the last good one stays in use, so a bad edit can't
 * silently stop backups. Runs missed while the machine was off aren't caught up.
 */
@Slf4j
public class BackupDaemon {
    private static final int MAX_CONCURRENT_JOBS = 4;

    private final Supplier<BackupSettings> loader;
    private final BiConsumer<JobConfig, BackupSettings> runner;
    private final ExecutorService pool = Executors.newFixedThreadPool(MAX_CONCURRENT_JOBS);
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile boolean stopping;
    private BackupSettings settings;

    /**
     * Creates a daemon and loads its settings.
     *
     * @param loader reads the settings, throwing ConfigException when the config is invalid
     * @param runner runs one job
     */
    public BackupDaemon(Supplier<BackupSettings> loader, BiConsumer<JobConfig, BackupSettings> runner) {
        this.loader = loader;
        this.runner = runner;
        this.settings = loader.get();
    }

    /**
     * Gives the settings currently in use.
     *
     * @return the last good settings
     */
    public BackupSettings settings() {
        return settings;
    }

    /**
     * Runs scheduled jobs, blocking until the JVM shuts down.
     *
     * @return the exit code; non-zero if another daemon is already running
     * @throws IOException if the single-instance lock can't be taken
     */
    public int run() throws IOException {
        Path lockFile = AppPaths.home().resolve("daemon.lock");
        Files.createDirectories(lockFile.getParent());
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) {
                log.error("Another daemon is already running with home {}", AppPaths.home());
                return 1;
            }
            Thread main = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                stopping = true;
                main.interrupt();
                try {
                    // Waits on this latch, not on main: main goes on to call System.exit, which blocks until hooks finish.
                    stopped.await(35, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "daemon-shutdown"));

            log.info("Daemon started with {} scheduled job(s)", scheduledCount());
            loop();
            shutdownPool();
            stopped.countDown();
            return 0;
        }
    }

    private long scheduledCount() {
        return settings.getJobs().values().stream().filter(job -> job.getSchedule() != null).count();
    }

    private void loop() {
        while (!stopping) {
            ZonedDateTime next = ZonedDateTime.now().truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
            try {
                Thread.sleep(Math.max(0, Duration.between(ZonedDateTime.now(), next).toMillis()));
            } catch (InterruptedException e) {
                return;
            }
            try {
                tick(next);
            } catch (RuntimeException e) {
                log.error("Scheduler error; continuing", e);
            }
        }
    }

    /**
     * Reloads config and starts every job due at a given minute. Skips a job still running from last time.
     *
     * @param minute the minute to check
     */
    public void tick(ZonedDateTime minute) {
        reload();
        BackupSettings current = settings;
        for (JobConfig job : current.getJobs().values()) {
            if (job.getSchedule() == null || !CronSchedule.parse(job.getSchedule()).matches(minute)) continue;
            if (!running.add(job.getName())) {
                log.warn("Skipping job '{}' at {}: its previous run is still going", job.getName(), minute);
                continue;
            }
            pool.submit(() -> {
                try {
                    runner.accept(job, current);
                } catch (Throwable t) {
                    log.error("Job '{}' crashed", job.getName(), t);
                } finally {
                    running.remove(job.getName());
                }
            });
        }
    }

    private void reload() {
        try {
            settings = loader.get();
        } catch (RuntimeException e) {
            log.error("Config is invalid; keeping the last good version. {}", e.getMessage());
        }
    }

    /** Gives running jobs time to finish, then kills them; killed runs leave only a .partial file. */
    public void shutdownPool() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Stopping jobs that are still running");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Daemon stopped");
    }
}
