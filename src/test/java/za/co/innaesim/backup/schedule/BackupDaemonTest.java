package za.co.innaesim.backup.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import za.co.innaesim.backup.config.ConfigException;
import za.co.innaesim.backup.model.BackupSettings;
import za.co.innaesim.backup.model.JobConfig;

class BackupDaemonTest {
    private static final ZonedDateTime TWO_AM = ZonedDateTime.of(2026, 6, 15, 2, 0, 0, 0, ZoneId.of("UTC"));

    private static BackupSettings settings(String... nameAndCron) {
        BackupSettings settings = new BackupSettings();
        for (int i = 0; i < nameAndCron.length; i += 2) {
            JobConfig job = new JobConfig();
            job.setName(nameAndCron[i]);
            job.setSchedule(nameAndCron[i + 1]);
            settings.getJobs().put(job.getName(), job);
        }
        return settings;
    }

    @Test
    void cronParsesAndMatches() {
        CronSchedule schedule = CronSchedule.parse("0 2 * * *");
        assertTrue(schedule.matches(TWO_AM));
        assertTrue(schedule.matches(TWO_AM.plusSeconds(30)), "any second within the minute");
        assertFalse(schedule.matches(TWO_AM.plusMinutes(1)));
        assertEquals(TWO_AM.plusDays(1), schedule.nextAfter(TWO_AM).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("nope"));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("99 * * * *"));
    }

    @Test
    void runsOnlyJobsThatAreDue() throws InterruptedException {
        List<String> ran = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        BackupDaemon daemon = new BackupDaemon(() -> settings("due", "0 2 * * *", "later", "0 3 * * *", "manual", null), (job, s) -> {
            ran.add(job.getName());
            done.countDown();
        });
        daemon.tick(TWO_AM);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        daemon.shutdownPool();
        assertEquals(List.of("due"), ran);
    }

    @Test
    void skipsAJobStillRunning() throws InterruptedException {
        List<String> ran = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BackupDaemon daemon = new BackupDaemon(() -> settings("slow", "* * * * *"), (job, s) -> {
            ran.add(job.getName());
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        daemon.tick(TWO_AM);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        daemon.tick(TWO_AM.plusMinutes(1));
        release.countDown();
        daemon.shutdownPool();
        assertEquals(1, ran.size());
    }

    @Test
    void picksUpNewSchedulesAndSurvivesInvalidConfig() throws InterruptedException {
        AtomicReference<BackupSettings> current = new AtomicReference<>(settings("a", "0 2 * * *"));
        AtomicReference<Boolean> broken = new AtomicReference<>(false);
        List<String> ran = new CopyOnWriteArrayList<>();
        BackupDaemon daemon = new BackupDaemon(() -> {
            if (broken.get()) throw new ConfigException("bad edit");
            return current.get();
        }, (job, s) -> ran.add(job.getName()));

        current.set(settings("a", "0 2 * * *", "b", "0 2 * * *"));
        daemon.tick(TWO_AM);
        waitFor(ran, 2);
        assertEquals(2, ran.size(), "new job picked up without a restart");

        broken.set(true);
        ran.clear();
        daemon.tick(TWO_AM.plusDays(1));
        waitFor(ran, 2);
        daemon.shutdownPool();
        assertEquals(2, ran.size(), "last good config keeps running");
    }

    @Test
    void aCrashingJobDoesNotStopTheScheduler() throws InterruptedException {
        List<String> ran = new CopyOnWriteArrayList<>();
        BackupDaemon daemon = new BackupDaemon(() -> settings("bad", "* * * * *"), (job, s) -> {
            ran.add(job.getName());
            throw new OutOfMemoryError("boom");
        });
        daemon.tick(TWO_AM);
        waitFor(ran, 1);
        Thread.sleep(100);
        daemon.tick(TWO_AM.plusMinutes(1));
        waitFor(ran, 2);
        daemon.shutdownPool();
        assertEquals(2, ran.size());
    }

    private static void waitFor(List<String> list, int size) throws InterruptedException {
        for (int i = 0; i < 100 && list.size() < size; i++) Thread.sleep(20);
    }
}
