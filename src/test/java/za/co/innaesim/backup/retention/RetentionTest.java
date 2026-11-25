package za.co.innaesim.backup.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import za.co.innaesim.backup.model.AppConfig;
import za.co.innaesim.backup.model.RetentionConfig;

class RetentionTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 15, 12, 0);

    private static AppConfig db() {
        AppConfig db = new AppConfig();
        db.setType("mysql");
        db.setName("shop");
        return db;
    }

    /** Backups taken 0, 1, 2... days ago, newest first. */
    private static List<BackupEntry> daily(int count) {
        List<BackupEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) entries.add(new BackupEntry("d" + i, NOW.minusDays(i)));
        return entries;
    }

    @Test
    void noPolicyDeletesNothing() {
        assertTrue(RetentionPolicy.toDelete(daily(5), null, NOW).isEmpty());
    }

    @Test
    void daysDeletesOlderOnly() {
        assertEquals(List.of("d3", "d4"), RetentionPolicy.toDelete(daily(5), new RetentionConfig(2, null), NOW));
    }

    @Test
    void keepLastDeletesBeyondCount() {
        assertEquals(List.of("d2", "d3", "d4"), RetentionPolicy.toDelete(daily(5), new RetentionConfig(null, 2), NOW));
    }

    @Test
    void aBackupSurvivesIfEitherRuleKeepsIt() {
        assertEquals(List.of("d4"), RetentionPolicy.toDelete(daily(5), new RetentionConfig(1, 4), NOW));
        assertEquals(List.of("d4"), RetentionPolicy.toDelete(daily(5), new RetentionConfig(3, 1), NOW));
    }

    @Test
    void theNewestSurvivesEvenWhenExpired() {
        List<BackupEntry> old = List.of(new BackupEntry("a", NOW.minusDays(100)), new BackupEntry("b", NOW.minusDays(101)));
        assertEquals(List.of("b"), RetentionPolicy.toDelete(old, new RetentionConfig(1, null), NOW));
    }

    @Test
    void namesRoundTripAndAreSortedNewestFirst() {
        BackupNaming naming = new BackupNaming(db());
        String older = naming.fileName(NOW.minusDays(1), true);
        String newer = naming.fileName(NOW, false);
        assertEquals("shop_20260615_120000.sql", newer);
        assertEquals("shop_20260614_120000.sql.gz", older);
        List<BackupEntry> entries = naming.entries(List.of(older, newer));
        assertEquals(newer, entries.get(0).name());
        assertEquals(NOW, entries.get(0).createdAt());
    }

    @Test
    void unrelatedFilesAreNeverRecognised() {
        BackupNaming naming = new BackupNaming(db());
        List<BackupEntry> entries = naming.entries(List.of(
                "shop_20260615_120000.sql.partial",
                "shop_20261399_990000.sql",          // not a real date
                "shopping_20260615_120000.sql",      // another database's backup
                "other_20260615_120000.sql",
                "notes.txt",
                "shop_20260615_120000.sql.bak"));
        assertTrue(entries.isEmpty(), entries.toString());
    }

    @Test
    void customPatternsWork() {
        AppConfig db = db();
        db.setFilepattern("nightly-{date}-{db}{ext}");
        BackupNaming naming = new BackupNaming(db);
        String name = naming.fileName(NOW, false);
        assertEquals("nightly-20260615_120000-shop.sql", name);
        assertEquals(1, naming.entries(List.of(name)).size());
    }
}
