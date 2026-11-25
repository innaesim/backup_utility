package za.co.innaesim.backup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import za.co.innaesim.backup.model.AppConfig;
import za.co.innaesim.backup.model.DestinationConfig;
import za.co.innaesim.backup.model.JobConfig;
import za.co.innaesim.backup.service.FakeTools.MemoryStore;
import za.co.innaesim.backup.service.RestoreService.Request;

@DisabledOnOs(OS.WINDOWS)
class RestoreServiceTest {
    @TempDir
    Path tools;
    @TempDir
    Path output;

    private final MemoryStore store = new MemoryStore();
    private final List<RunReport> sent = new ArrayList<>();
    private RestoreService service;
    private Path received;

    @BeforeEach
    void setUp() throws IOException {
        AppConfig db = new AppConfig();
        db.setType("mysql");
        db.setHost("dbhost");
        db.setPort(3306);
        db.setName("shop");
        db.setOutputDir(output.toString());
        JobConfig job = new JobConfig();
        job.setName("shop");
        job.setDatabase(db);
        DestinationConfig destination = new DestinationConfig();
        destination.setName("remote");
        destination.setType("ftp");
        service = new RestoreService(job, Map.of("remote", destination), FakeTools.in(tools), store.factory(), sent::add);
        received = tools.resolve("received");
        FakeTools.script(tools, "mysql", "cat > '" + received + "'");
    }

    private static Request yes(String file, String from, String target) {
        return new Request(file, from, target, null, null);
    }

    @Test
    void restoresTheLatestLocalBackup() throws IOException {
        Files.writeString(output.resolve("shop_20260101_000000.sql"), "older");
        Files.writeString(output.resolve("shop_20260102_000000.sql"), "newest");
        Files.writeString(output.resolve("shop_20260103_000000.sql.partial"), "unfinished");
        RunReport report = service.restore(yes(null, null, null));
        assertTrue(report.succeeded(), report.error());
        assertEquals("shop_20260102_000000.sql", report.file());
        assertEquals("newest", Files.readString(received));
    }

    @Test
    void decompressesGzip() throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(output.resolve("shop_20260101_000000.sql.gz")))) {
            out.write("zipped".getBytes());
        }
        assertTrue(service.restore(yes(null, null, null)).succeeded());
        assertEquals("zipped", Files.readString(received));
    }

    @Test
    void fetchesFromADestination() throws IOException {
        store.files.put("shop_20260101_000000.sql", "remote-data".getBytes());
        RunReport report = service.restore(yes(null, "remote", null));
        assertTrue(report.succeeded(), report.error());
        assertEquals("remote-data", Files.readString(received));
    }

    @Test
    void rejectsPathsAsRemoteNames() {
        RunReport report = service.restore(yes("../../etc/passwd", "remote", null));
        assertFalse(report.succeeded());
        assertTrue(report.error().contains("plain file name"), report.error());
    }

    @Test
    void wrongConfirmationChangesNothingAndSendsNoAlert() throws IOException {
        Files.writeString(output.resolve("shop_20260101_000000.sql"), "data");
        for (String answer : new String[] {"y", "yes", "", "SHOP", null}) {
            RunReport report = service.restore(new Request(null, null, null, null, prompt -> answer));
            assertFalse(report.succeeded(), "answer: " + answer);
        }
        assertFalse(Files.exists(received), "the tool must not run");
        assertTrue(sent.isEmpty());
    }

    @Test
    void confirmationNamesTheTargetDatabase() throws IOException {
        Files.writeString(output.resolve("shop_20260101_000000.sql"), "data");
        List<String> prompts = new ArrayList<>();
        RunReport report = service.restore(new Request(null, null, "staging", null, prompt -> {
            prompts.add(prompt);
            return "staging";
        }));
        assertTrue(report.succeeded(), report.error());
        assertTrue(prompts.get(0).contains("dbhost:3306/staging"), prompts.get(0));
        assertTrue(Files.readString(tools.resolve("mysql.log")).contains("staging"));
        assertEquals(1, sent.size());
    }

    @Test
    void toolFailureIsReported() throws IOException {
        FakeTools.script(tools, "mysql", "cat > /dev/null; echo 'syntax error' >&2; exit 1");
        Files.writeString(output.resolve("shop_20260101_000000.sql"), "data");
        RunReport report = service.restore(yes(null, null, null));
        assertFalse(report.succeeded());
        assertTrue(report.error().contains("syntax error"), report.error());
        assertEquals(1, sent.size());
    }

    @Test
    void noBackupsIsAClearError() {
        RunReport report = service.restore(yes(null, null, null));
        assertFalse(report.succeeded());
        assertTrue(report.error().contains("No backups"), report.error());
    }

    private RestoreService folderService() {
        AppConfig config = new AppConfig();
        config.setType("folder");
        config.setName("files");
        config.setPaths(List.of(tools.resolve("docs").toString()));
        config.setOutputDir(output.toString());
        JobConfig job = new JobConfig();
        job.setName("files");
        job.setDatabase(config);
        DestinationConfig destination = new DestinationConfig();
        destination.setName("remote");
        destination.setType("ftp");
        return new RestoreService(job, Map.of("remote", destination), FakeTools.in(tools), store.factory(), sent::add);
    }

    private byte[] folderBackup(boolean gzip) throws IOException {
        Path docs = Files.createDirectories(tools.resolve("docs/sub"));
        Files.writeString(docs.resolve("b.txt"), "bravo");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (OutputStream out = gzip ? new GZIPOutputStream(bytes) : bytes) {
            za.co.innaesim.backup.folder.FolderArchiver.write(List.of(tools.resolve("docs")),
                    za.co.innaesim.backup.folder.Excludes.NONE, java.util.Set.of(), out);
        }
        return bytes.toByteArray();
    }

    private static Request intoDir(String from, Path dir) {
        return new Request(null, from, null, dir, null);
    }

    @Test
    void restoresTheLatestFolderBackupIntoANewFolder() throws IOException {
        Files.write(output.resolve("files_20260101_000000.tar"), new byte[0]);
        Files.write(output.resolve("files_20260102_000000.tar.gz"), folderBackup(true));
        Path target = tools.resolve("restored");

        RunReport report = folderService().restore(intoDir(null, target));

        assertTrue(report.succeeded(), report.error());
        assertEquals("files_20260102_000000.tar.gz", report.file());
        assertEquals("bravo", Files.readString(target.resolve("docs/sub/b.txt")));
        assertEquals(1, sent.size());
    }

    @Test
    void restoresAFolderBackupFromADestination() throws IOException {
        store.files.put("files_20260102_000000.tar", folderBackup(false));
        Path target = tools.resolve("restored");

        RunReport report = folderService().restore(intoDir("remote", target));

        assertTrue(report.succeeded(), report.error());
        assertEquals("bravo", Files.readString(target.resolve("docs/sub/b.txt")));
    }

    @Test
    void folderRestoreRefusesANonEmptyFolderBeforeDoingAnything() throws IOException {
        Files.write(output.resolve("files_20260102_000000.tar"), folderBackup(false));
        Path target = Files.createDirectories(tools.resolve("restored"));
        Files.writeString(target.resolve("keep.txt"), "mine");

        RunReport report = folderService().restore(intoDir(null, target));

        assertFalse(report.succeeded());
        assertTrue(report.error().contains("isn't empty"), report.error());
        assertFalse(Files.exists(target.resolve("docs")));
        assertEquals(List.of(), sent, "nothing was written, so nobody is alerted");
    }

    @Test
    void folderRestoreNeedsATargetDir() {
        RunReport report = folderService().restore(intoDir(null, null));
        assertFalse(report.succeeded());
        assertTrue(report.error().contains("--target-dir"), report.error());
    }

    @Test
    void databaseRestoreRefusesATargetDir() {
        RunReport report = service.restore(new Request(null, null, null, tools.resolve("x"), null));
        assertFalse(report.succeeded());
        assertTrue(report.error().contains("--target-dir is for folder jobs"), report.error());
        assertEquals(List.of(), sent);
    }
}
