package za.co.innaesim.backup.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import za.co.innaesim.backup.config.ConfigException;
import za.co.innaesim.backup.config.ConfigLoader;
import za.co.innaesim.backup.model.BackupSettings;
import za.co.innaesim.backup.model.JobConfig;

class ConfigLoaderTest {
    private static final Function<String, String> ENV = Map.of("ORDERS_PW", "p@ss: \"word\"", "SLACK_URL", "https://hooks.example/x")::get;

    @TempDir
    Path dir;

    private BackupSettings load(String yaml) throws IOException {
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, yaml);
        return ConfigLoader.load(file, true, dir.resolve("schedules.d"), ENV);
    }

    private List<String> problems(String yaml) {
        return assertThrows(ConfigException.class, () -> load(yaml)).getProblems();
    }

    @Test
    void loadsAFullConfig() throws URISyntaxException {
        Path file = Path.of(getClass().getResource("/test-config.yaml").toURI());
        BackupSettings settings = ConfigLoader.load(file, true, dir.resolve("none"), ENV);

        JobConfig orders = settings.getJobs().get("orders");
        assertEquals("postgresql", orders.getDatabase().getType());
        assertEquals("localhost", orders.getDatabase().getHost());
        assertEquals(5432, orders.getDatabase().getPort());
        assertEquals("p@ss: \"word\"", orders.getDatabase().getPassword(), "special characters in secrets survive");
        assertEquals(file.getParent().resolve("out").toString(), orders.getDatabase().getOutputDir(), "relative to the config file");
        assertEquals(List.of("offsite", "nas"), orders.getDestinations());
        assertEquals(30, orders.getRetention().days());
        assertEquals(5, orders.getRetention().keepLast());
        assertEquals(27017, settings.getJobs().get("catalog").getDatabase().getPort());
        assertEquals("https://hooks.example/x", settings.getSlackWebhookUrl());
        assertEquals(Set.of("failure"), settings.getSlackNotifyOn());
        assertEquals(System.getProperty("user.home") + "/.ssh/id_ed25519", settings.getDestinations().get("nas").getKeyFile());
    }

    @Test
    void reportsEveryProblemAtOnce() {
        List<String> problems = problems("""
                destinations:
                  bad: {type: dropbox}
                jobs:
                  one:
                    type: oracle
                    name: x
                    schedule: "not cron"
                    destinations: [nowhere]
                    retention: {days: 0}
                    file_pattern: fixed.sql
                    retentin: oops
                """);
        String all = String.join("\n", problems);
        assertTrue(all.contains("dropbox"), all);
        assertTrue(all.contains("oracle"), all);
        assertTrue(all.contains("not cron"), all);
        assertTrue(all.contains("nowhere"), all);
        assertTrue(all.contains("days must be at least 1"), all);
        assertTrue(all.contains("{date}"), all);
        assertTrue(all.contains("retentin"), all);
    }

    @Test
    void missingEnvVarIsAnError() {
        String all = String.join("\n", problems("jobs: {a: {type: mysql, name: a, password: '${NOPE}'}}"));
        assertTrue(all.contains("${NOPE}"), all);
    }

    @Test
    void refusesJobsThatWouldDeleteEachOthersBackups() {
        String all = String.join("\n", problems("""
                jobs:
                  a: {type: mysql, name: shop, output_dir: same}
                  b: {type: mysql, name: shop, host: other, output_dir: same}
                """));
        assertTrue(all.contains("retention"), all);
    }

    @Test
    void refusesPathTricksInNames() {
        assertFalse(problems("jobs: {'../evil': {type: mysql, name: a}}").isEmpty());
        assertFalse(problems("jobs: {a: {type: mysql, name: '../../etc'}}").isEmpty());
        assertFalse(problems("jobs: {a: {type: mysql, name: a, file_pattern: '../{date}.sql'}}").isEmpty());
    }

    @Test
    void schedulesDirectoryMergesOverJobs() throws IOException {
        Path schedules = dir.resolve("schedules.d");
        Files.createDirectories(schedules);
        Files.writeString(schedules.resolve("a.yaml"), "jobs: {a: {schedule: '*/5 * * * *'}}");
        Files.writeString(schedules.resolve("b.yaml"), "jobs: {b: {type: mysql, name: b, schedule: '0 1 * * *'}}");
        BackupSettings settings = load("jobs: {a: {type: mysql, name: a}}");
        assertEquals("*/5 * * * *", settings.getJobs().get("a").getSchedule());
        assertEquals("a", settings.getJobs().get("a").getDatabase().getName());
        assertEquals("0 1 * * *", settings.getJobs().get("b").getSchedule());
    }

    @Test
    void explicitConfigMustExistButDefaultMayNot() {
        Path missing = dir.resolve("missing.yaml");
        assertThrows(ConfigException.class, () -> ConfigLoader.load(missing, true, dir, ENV));
        assertTrue(ConfigLoader.load(missing, false, dir, ENV).getJobs().isEmpty());
    }

    @Test
    void brokenYamlIsAConfigError() {
        assertThrows(ConfigException.class, () -> load("jobs: [unclosed"));
    }

    @Test
    void loadsAFolderJob() throws IOException {
        BackupSettings settings = load("""
                jobs:
                  documents:
                    type: folder
                    paths: [docs, ~/uploads, ../shared]
                    exclude: ["node_modules", "*.tmp"]
                    compress: true
                """);
        var config = settings.getJobs().get("documents").getDatabase();
        assertEquals("folder", config.getType());
        assertEquals("documents", config.getName(), "the job name stands in for {db}");
        assertEquals(List.of(dir.resolve("docs").toString(), Path.of(System.getProperty("user.home"), "uploads").toString(),
                dir.getParent().resolve("shared").toString()), config.getPaths());
        assertEquals(List.of("node_modules", "*.tmp"), config.getExcludes());
    }

    @Test
    void folderJobProblemsAreReported() {
        List<String> missing = problems("""
                jobs:
                  a:
                    type: folder
                    host: db.internal
                """);
        assertTrue(missing.stream().anyMatch(p -> p.contains("jobs.a.paths is required")), missing.toString());
        assertTrue(missing.stream().anyMatch(p -> p.contains("host")), "connection settings mean nothing here: " + missing);

        List<String> bad = problems("""
                jobs:
                  b:
                    type: folder
                    paths: [one/data, two/data]
                    exclude: ["[oops"]
                """);
        assertTrue(bad.stream().anyMatch(p -> p.contains("both named 'data'")), bad.toString());
        assertTrue(bad.stream().anyMatch(p -> p.contains("jobs.b.exclude")), bad.toString());
    }
}
