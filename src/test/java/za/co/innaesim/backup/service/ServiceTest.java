package za.co.innaesim.backup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import za.co.innaesim.backup.database.Drivers;
import za.co.innaesim.backup.model.AppConfig;
import za.co.innaesim.backup.model.DestinationConfig;
import za.co.innaesim.backup.model.JobConfig;
import za.co.innaesim.backup.model.RetentionConfig;
import za.co.innaesim.backup.service.FakeTools.MemoryStore;

@DisabledOnOs(OS.WINDOWS)
class ServiceTest {
    @TempDir
    Path tools;
    @TempDir
    Path output;

    private final List<RunReport> sent = new ArrayList<>();
    private final MemoryStore store = new MemoryStore();
    private JobConfig job;

    @BeforeEach
    void setUp() {
        job = job("mysql", "shop");
    }

    private JobConfig job(String type, String name) {
        AppConfig db = new AppConfig();
        db.setType(type);
        db.setHost("dbhost");
        db.setPort(Drivers.forType(type).defaultPort());
        db.setName(name);
        db.setUser("bob");
        db.setPassword("s3cr3t\"pw");
        db.setOutputDir(output.toString());
        db.setCompress(false);
        JobConfig config = new JobConfig();
        config.setName(type + "-" + name);
        config.setDatabase(db);
        return config;
    }

    private Service service(Map<String, DestinationConfig> destinations) {
        return new Service(job, destinations, FakeTools.in(tools), store.factory(), sent::add, 1);
    }

    private RunReport run() {
        return service(Map.of()).runBackup();
    }

    private List<String> outputFiles() throws IOException {
        try (var files = Files.list(output)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static Map<String, DestinationConfig> destinations(String... names) {
        Map<String, DestinationConfig> out = new java.util.LinkedHashMap<>();
        for (String name : names) {
            DestinationConfig d = new DestinationConfig();
            d.setName(name);
            d.setType("ftp");
            d.setPassword("hunter2");
            out.put(name, d);
        }
        return out;
    }

    @Test
    void writesTheDumpToTheFile() throws IOException {
        FakeTools.script(tools, "mysqldump", "echo 'CREATE TABLE t;'");
        RunReport report = run();
        assertTrue(report.succeeded(), report.error());
        assertEquals("CREATE TABLE t;\n", Files.readString(output.resolve(report.file())), "the original bug left this file empty");
        assertEquals(List.of(report.file()), outputFiles(), "no .partial left behind");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(output.resolve(report.file()))));
        assertEquals(1, sent.size());
    }

    @Test
    void compressesToGzipWithGzExtension() throws IOException {
        FakeTools.script(tools, "mysqldump", "echo hello");
        job.getDatabase().setCompress(true);
        RunReport report = run();
        assertTrue(report.file().endsWith(".sql.gz"), report.file());
        try (InputStream in = new GZIPInputStream(Files.newInputStream(output.resolve(report.file())))) {
            assertEquals("hello\n", new String(in.readAllBytes()));
        }
    }

    @Test
    void failedToolIsAFailureAndLeavesNoFile() throws IOException {
        FakeTools.script(tools, "mysqldump", "echo partial-data; echo 'access denied for s3cr3t\"pw' >&2; exit 2");
        RunReport report = run();
        assertFalse(report.succeeded());
        assertTrue(report.error().contains("exit code 2"), report.error());
        assertTrue(report.error().contains("access denied"), report.error());
        assertFalse(report.error().contains("s3cr3t"), "password must be scrubbed: " + report.error());
        assertTrue(outputFiles().isEmpty(), outputFiles().toString());
        assertEquals(1, sent.size(), "failures are notified too");
    }

    @Test
    void emptyDumpIsAFailure() throws IOException {
        FakeTools.script(tools, "mysqldump", "exit 0");
        RunReport report = run();
        assertFalse(report.succeeded());
        assertTrue(report.error().contains("no data"), report.error());
        assertTrue(outputFiles().isEmpty());
    }

    @Test
    void missingToolIsAFailureNotACrash() {
        RunReport report = run();
        assertFalse(report.succeeded());
        assertTrue(report.error().contains("mysqldump"), report.error());
    }

    @Test
    void largeStderrCannotDeadlock() throws IOException {
        FakeTools.script(tools, "mysqldump", "i=0; while [ $i -lt 3000 ]; do echo 'warning line warning line warning line warning line' >&2; i=$((i+1)); done; echo done");
        RunReport report = run();
        assertTrue(report.succeeded(), report.error());
    }

    @Test
    void mysqlPasswordStaysOutOfArgsAndTempFileIsRemoved() throws IOException {
        FakeTools.script(tools, "mysqldump", "cat \"$(echo \"$1\" | sed 's/--defaults-extra-file=//')\" > '" + tools + "/seen'; echo \"$1\" > '" + tools + "/optfile'; echo data");
        assertTrue(run().succeeded());
        String log = Files.readString(tools.resolve("mysqldump.log"));
        assertFalse(log.contains("s3cr3t"), log);
        assertTrue(log.contains("--defaults-extra-file="), log);
        assertEquals("[client]\npassword=\"s3cr3t\\\"pw\"\n", Files.readString(tools.resolve("seen")));
        String tempFile = Files.readString(tools.resolve("optfile")).strip().replace("--defaults-extra-file=", "");
        assertFalse(Files.exists(Path.of(tempFile)), "temp password file must be deleted");
    }

    @Test
    void mysqlDumpsAreRestorableOnGtidServers() throws IOException {
        FakeTools.script(tools, "mysqldump", "case \"$1\" in --version) echo 'mysqldump Ver 8.4'; exit 0;; esac; echo data");
        assertTrue(run().succeeded());
        assertTrue(Files.readString(tools.resolve("mysqldump.log")).contains("--set-gtid-purged=OFF"));
    }

    @Test
    void mariaDbDumpDoesNotGetTheGtidOption() throws IOException {
        FakeTools.script(tools, "mysqldump", "case \"$1\" in --version) echo 'mysqldump from 11.4-MariaDB'; exit 0;; esac; echo data");
        assertTrue(run().succeeded());
        assertFalse(Files.readString(tools.resolve("mysqldump.log")).contains("gtid"));
    }

    @Test
    void postgresGetsPasswordFromEnvironmentAndNeverPrompts() throws IOException {
        job = job("postgresql", "shop");
        FakeTools.script(tools, "pg_dump", "echo data");
        assertTrue(run().succeeded());
        String log = Files.readString(tools.resolve("pg_dump.log"));
        assertTrue(log.contains("pgpassword: s3cr3t\"pw"), log);
        assertTrue(log.contains("--no-password"), log);
        assertFalse(log.contains("args: " + "s3cr3t"), log);
    }

    @Test
    void mongoUsesArchiveAndConfigFile() throws IOException {
        job = job("mongodb", "shop");
        FakeTools.script(tools, "mongodump", "echo archive-bytes");
        RunReport report = run();
        assertTrue(report.succeeded(), report.error());
        assertTrue(report.file().endsWith(".archive"), report.file());
        String log = Files.readString(tools.resolve("mongodump.log"));
        assertTrue(log.contains("--db=shop"), log);
        assertTrue(log.contains("--archive"), log);
        assertTrue(log.contains("--config="), log);
        assertFalse(log.contains("s3cr3t"), log);
    }

    @Test
    void uploadRetriesThenSucceeds() throws IOException {
        FakeTools.script(tools, "mysqldump", "echo data");
        job.setDestinations(List.of("a"));
        store.failuresLeft = 2;
        RunReport report = service(destinations("a")).runBackup();
        assertTrue(report.succeeded(), report.error());
        assertEquals(3, store.uploads);
        assertEquals("uploaded", report.destinations().get("a"));
        assertNotNull(store.files.get(report.file()));
    }

    @Test
    void uploadFailureFailsTheJobButKeepsLocalCopyAndSkipsRetention() throws IOException {
        FakeTools.script(tools, "mysqldump", "echo data");
        Files.writeString(output.resolve("shop_20200101_000000.sql"), "old");
        job.setDestinations(List.of("a"));
        job.setRetention(new RetentionConfig(1, null));
        store.failuresLeft = 99;
        RunReport report = service(destinations("a")).runBackup();
        assertFalse(report.succeeded());
        assertEquals(3, store.uploads);
        assertTrue(Files.exists(output.resolve(report.file())), "local copy kept");
        assertTrue(Files.exists(output.resolve("shop_20200101_000000.sql")), "old backup kept when an upload failed");
        assertFalse(report.destinations().get("a").contains("hunter2"), "destination password must be scrubbed");
    }

    @Test
    void wrongUploadSizeIsDetected() throws IOException {
        FakeTools.script(tools, "mysqldump", "echo data");
        job.setDestinations(List.of("a"));
        store.truncate = true;
        RunReport report = service(destinations("a")).runBackup();
        assertFalse(report.succeeded());
        assertTrue(report.destinations().get("a").contains("expected"), report.destinations().toString());
    }

    @Test
    void retentionCleansLocalAndRemoteAndLeavesOtherFilesAlone() throws IOException {
        FakeTools.script(tools, "mysqldump", "echo data");
        Files.writeString(output.resolve("shop_20200101_000000.sql"), "old");
        Files.writeString(output.resolve("shop_20200101_000000.sql.partial"), "unfinished");
        Files.writeString(output.resolve("other_20200101_000000.sql"), "another db");
        Files.writeString(output.resolve("notes.txt"), "mine");
        store.files.put("shop_20200102_000000.sql", new byte[1]);
        store.files.put("unrelated.bin", new byte[1]);
        job.setDestinations(List.of("a"));
        job.setRetention(new RetentionConfig(1, null));

        RunReport report = service(destinations("a")).runBackup();

        assertTrue(report.succeeded(), report.error());
        assertFalse(Files.exists(output.resolve("shop_20200101_000000.sql")));
        assertTrue(Files.exists(output.resolve("shop_20200101_000000.sql.partial")));
        assertTrue(Files.exists(output.resolve("other_20200101_000000.sql")));
        assertTrue(Files.exists(output.resolve("notes.txt")));
        assertEquals(List.of("shop_20200102_000000.sql"), store.deleted);
        assertTrue(store.files.containsKey("unrelated.bin"));
        assertEquals(2, report.notes().size(), report.notes().toString());
    }

    @Test
    void failedBackupNeverRunsRetention() throws IOException {
        FakeTools.script(tools, "mysqldump", "exit 1");
        Files.writeString(output.resolve("shop_20200101_000000.sql"), "old");
        job.setRetention(new RetentionConfig(1, null));
        assertFalse(run().succeeded());
        assertTrue(Files.exists(output.resolve("shop_20200101_000000.sql")));
    }

    @Test
    void sameJobCannotRunTwiceAtOnce() throws Exception {
        FakeTools.script(tools, "mysqldump", "echo data");
        try (JobLock held = JobLock.acquire(job.getName())) {
            RunReport report = run();
            assertFalse(report.succeeded());
            assertTrue(report.error().contains("already running"), report.error());
        }
        RunReport after = run();
        assertNull(after.error());
    }

    private JobConfig folderJob(Path... folders) {
        AppConfig config = new AppConfig();
        config.setType("folder");
        config.setName("files");
        config.setPaths(java.util.Arrays.stream(folders).map(Path::toString).toList());
        config.setOutputDir(output.toString());
        config.setCompress(false);
        JobConfig folderJob = new JobConfig();
        folderJob.setName("files");
        folderJob.setDatabase(config);
        return folderJob;
    }

    private static List<String> tarNames(InputStream in) throws IOException {
        List<String> names = new ArrayList<>();
        try (var tar = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(in)) {
            for (var entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) names.add(entry.getName());
        }
        return names;
    }

    @Test
    void backsUpAFolderAsATarWithoutAnyTools() throws IOException {
        Path docs = Files.createDirectories(tools.resolve("docs"));
        Files.writeString(docs.resolve("a.txt"), "alpha");
        job = folderJob(docs);

        RunReport report = run();

        assertTrue(report.succeeded(), report.error());
        assertTrue(report.file().matches("files_\\d{8}_\\d{6}\\.tar"), report.file());
        assertEquals(List.of(report.file()), outputFiles(), "no .partial left behind");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(output.resolve(report.file()))));
        try (InputStream in = Files.newInputStream(output.resolve(report.file()))) {
            assertEquals(List.of("docs/", "docs/a.txt"), tarNames(in));
        }
        assertTrue(report.notes().contains("1 file(s) archived"), report.notes().toString());
        assertTrue(report.database().startsWith("folder "), report.database());
    }

    @Test
    void compressedFolderBackupIsUploadedAndCleanedByRetention() throws IOException {
        Path docs = Files.createDirectories(tools.resolve("docs"));
        Files.writeString(docs.resolve("a.txt"), "alpha");
        job = folderJob(docs);
        job.getDatabase().setCompress(true);
        job.setDestinations(List.of("a"));
        job.setRetention(new RetentionConfig(1, null));
        Files.writeString(output.resolve("files_20200101_000000.tar.gz"), "old");
        store.files.put("files_20200101_000000.tar", new byte[1]);

        RunReport report = service(destinations("a")).runBackup();

        assertTrue(report.succeeded(), report.error());
        assertTrue(report.file().endsWith(".tar.gz"), report.file());
        try (InputStream in = new GZIPInputStream(Files.newInputStream(output.resolve(report.file())))) {
            assertEquals(List.of("docs/", "docs/a.txt"), tarNames(in));
        }
        assertNotNull(store.files.get(report.file()));
        assertFalse(Files.exists(output.resolve("files_20200101_000000.tar.gz")));
        assertEquals(List.of("files_20200101_000000.tar"), store.deleted);
    }

    @Test
    void outputFolderInsideTheSourceIsNotArchived() throws IOException {
        Path docs = Files.createDirectories(tools.resolve("docs"));
        Files.writeString(docs.resolve("a.txt"), "alpha");
        job = folderJob(docs);
        job.getDatabase().setOutputDir(docs.resolve("backups").toString());

        RunReport first = run();
        RunReport second = run();

        assertTrue(second.succeeded(), second.error());
        try (InputStream in = Files.newInputStream(docs.resolve("backups").resolve(second.file()))) {
            assertEquals(List.of("docs/", "docs/a.txt"), tarNames(in), "earlier backup " + first.file() + " must not be inside");
        }
    }

    @Test
    void missingFolderFailsAndLeavesNothing() throws IOException {
        job = folderJob(tools.resolve("ghost"));
        RunReport report = run();
        assertFalse(report.succeeded());
        assertTrue(report.error().startsWith("Folder not found"), report.error());
        assertEquals(List.of(), outputFiles());
        assertEquals(1, sent.size());
    }
}
