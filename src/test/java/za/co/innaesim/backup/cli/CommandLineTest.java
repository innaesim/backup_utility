package za.co.innaesim.backup.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import za.co.innaesim.backup.Main;
import za.co.innaesim.backup.config.AppPaths;

import picocli.CommandLine;

class CommandLineTest {
    @TempDir
    Path home;

    private String previousHome;
    private StringWriter err;

    @BeforeEach
    void useTempHome() {
        previousHome = System.getProperty(AppPaths.HOME_PROPERTY);
        System.setProperty(AppPaths.HOME_PROPERTY, home.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty(AppPaths.HOME_PROPERTY, previousHome);
    }

    private int run(String... args) {
        CommandLine commandLine = Main.commandLine();
        err = new StringWriter();
        commandLine.setErr(new PrintWriter(err));
        commandLine.setOut(new PrintWriter(new StringWriter()));
        return commandLine.execute(args);
    }

    @Test
    void helpAndVersionWork() {
        assertEquals(0, run("--help"));
        assertEquals(0, run("--version"));
        for (String command : new String[] {"backup", "restore", "list", "schedule", "daemon", "service", "doctor"}) {
            assertEquals(0, run(command, "--help"), command);
        }
    }

    @Test
    void thereIsNoPlainPasswordOption() {
        assertEquals(2, run("backup", "--type", "mysql", "--db", "a", "--password", "x"));
    }

    @Test
    void backupNeedsExactlyOneMode() {
        assertEquals(1, run("backup"));
        assertTrue(err.toString().contains("--job"), err.toString());
        assertEquals(1, run("backup", "--all", "--job", "x"));
    }

    @Test
    void unknownJobIsAClearError() {
        assertEquals(1, run("backup", "--job", "ghost"));
        assertTrue(err.toString().contains("ghost"), err.toString());
    }

    @Test
    void restoreRequiresAChoiceOfBackup() {
        assertEquals(2, run("restore", "--job", "x"));
        assertEquals(2, run("restore", "--job", "x", "--latest", "--file", "y"));
    }

    @Test
    void scheduleRoundTrip() throws IOException {
        Path output = home.resolve("out");
        assertEquals(0, run("schedule", "add", "--name", "nightly", "--cron", "0 2 * * *", "--type", "mysql",
                "--db", "shop", "--user", "bob", "--password-env", "PATH", "--output", output.toString(),
                "--retention-days", "7", "--compress"));
        Path file = home.resolve("schedules.d/nightly.yaml");
        String content = Files.readString(file);
        assertTrue(content.contains("${PATH}"), "only the variable name is stored: " + content);
        assertFalse(content.contains(System.getenv("PATH")), "the secret itself must not be written");
        assertTrue(content.replace("\\\\", "\\").contains(output.toString()), content); // YAML doubles Windows backslashes
        assertTrue(content.contains("days: 7"), content);
        assertEquals(0, run("schedule", "list"));
        assertEquals(0, run("schedule", "remove", "nightly"));
        assertFalse(Files.exists(file));
        assertEquals(1, run("schedule", "remove", "nightly"));
    }

    @Test
    void scheduleForExistingJobKeepsTheConfigFileUntouched() throws IOException {
        Path config = home.resolve("config.yaml");
        String original = "# my comment\njobs: {shop: {type: mysql, name: shop}}\n";
        Files.writeString(config, original);
        assertEquals(0, run("schedule", "add", "--job", "shop", "--cron", "*/5 * * * *"));
        assertEquals(original, Files.readString(config));
        assertTrue(Files.readString(home.resolve("schedules.d/shop.yaml")).contains("*/5 * * * *"));
    }

    @Test
    void invalidScheduleLeavesNothingBehind() {
        assertEquals(1, run("schedule", "add", "--name", "x", "--cron", "nope", "--type", "mysql", "--db", "x"));
        assertEquals(1, run("schedule", "add", "--name", "x", "--cron", "0 1 * * *", "--type", "mysql", "--db", "x", "--destination", "nowhere"));
        assertFalse(Files.exists(home.resolve("schedules.d/x.yaml")));
        assertEquals(1, run("schedule", "add", "--job", "ghost", "--cron", "0 1 * * *"));
        assertEquals(1, run("schedule", "add", "--name", "x", "--cron", "0 1 * * *", "--type", "mysql", "--db", "x", "--ask-password"));
    }

    @Test
    void listShowsBackupsFromTheOutputDirectory() throws IOException {
        Path config = home.resolve("config.yaml");
        Files.writeString(config, "jobs: {shop: {type: mysql, name: shop, output_dir: out}}\n");
        assertEquals(0, run("list", "--job", "shop"));
    }

    @Test
    void backsUpAFolderFromTheCommandLine() throws IOException {
        Path docs = Files.createDirectories(home.resolve("my docs"));
        Files.writeString(docs.resolve("a.txt"), "alpha");
        Files.writeString(docs.resolve("skip.tmp"), "x");
        Path output = home.resolve("out");

        assertEquals(0, run("backup", "--type", "folder", "--path", docs.toString(), "--exclude", "*.tmp", "--output", output.toString()), err.toString());

        try (var files = Files.list(output)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(1, names.size(), names.toString());
            assertTrue(names.get(0).matches("my_docs_\\d{8}_\\d{6}\\.tar"), names.toString());
        }
    }

    @Test
    void folderOptionsAreCheckedAgainstTheType() {
        assertEquals(1, run("backup", "--type", "folder", "--output", home.toString()));
        assertTrue(err.toString().contains("--path"), err.toString());
        assertEquals(1, run("backup", "--type", "folder", "--path", home.toString(), "--db", "x"));
        assertTrue(err.toString().contains("--db can't be used with --type folder"), err.toString());
        assertEquals(1, run("backup", "--type", "mysql", "--db", "x", "--path", home.toString()));
    }

    @Test
    void scheduleAddWritesAFolderJob() throws IOException {
        Path docs = Files.createDirectories(home.resolve("docs"));
        assertEquals(0, run("schedule", "add", "--name", "docs", "--cron", "0 3 * * *", "--type", "folder",
                "--path", docs.toString(), "--exclude", "node_modules", "--output", home.resolve("out").toString()), err.toString());
        String content = Files.readString(home.resolve("schedules.d/docs.yaml"));
        assertTrue(content.contains("type: folder"), content);
        assertTrue(content.contains("node_modules"), content);
        assertFalse(content.contains("host:"), content);
        assertEquals(0, run("schedule", "remove", "docs"));
    }
}
