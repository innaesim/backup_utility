package za.co.innaesim.backup.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class PlatformTest {
    @TempDir
    Path dir;

    private void executable(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void detectsOperatingSystems() {
        assertEquals(OperatingSystem.MACOS, OperatingSystem.fromName("Mac OS X"));
        assertEquals(OperatingSystem.LINUX, OperatingSystem.fromName("Linux"));
        assertEquals(OperatingSystem.WINDOWS, OperatingSystem.fromName("Windows 11"));
        assertEquals(OperatingSystem.UNKNOWN, OperatingSystem.fromName("Plan 9"));
        assertEquals(OperatingSystem.MACOS, OperatingSystem.fromName("Darwin"), "darwin contains 'win' but isn't Windows");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void findsToolsOnPathAndInExtraDirs() throws IOException {
        executable(dir.resolve("a/pg_dump"));
        executable(dir.resolve("versions/15/bin/psql"));
        executable(dir.resolve("versions/16/bin/psql"));
        ToolChecker checker = new ToolChecker(OperatingSystem.LINUX, dir.resolve("a").toString() + File.pathSeparator + "/nonexistent",
                List.of(dir + "/versions/*/bin"));
        assertEquals(dir.resolve("a/pg_dump"), checker.find("pg_dump").orElseThrow());
        assertEquals(dir.resolve("versions/16/bin/psql"), checker.find("psql").orElseThrow(), "newest version wins");
        assertTrue(checker.find("mongodump").isEmpty());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void ignoresNonExecutableFiles() throws IOException {
        Files.writeString(dir.resolve("pg_dump"), "not executable");
        assertTrue(new ToolChecker(OperatingSystem.LINUX, dir.toString(), List.of()).find("pg_dump").isEmpty());
    }

    @Test
    void installPlansPerPackageManager() {
        assertEquals(List.of("brew", "install", "libpq"), InstallPlan.forTool("pg_dump", PackageManager.BREW, false).command());
        assertEquals(List.of("brew", "install", "mysql-client"), InstallPlan.forTool("mysqldump", PackageManager.BREW, false).command());
        assertEquals(List.of("sudo", "apt-get", "install", "-y", "postgresql-client"), InstallPlan.forTool("psql", PackageManager.APT, false).command());
        assertEquals(List.of("apt-get", "install", "-y", "default-mysql-client"), InstallPlan.forTool("mysql", PackageManager.APT, true).command(), "no sudo as root");
        assertEquals(List.of("sudo", "dnf", "install", "-y", "postgresql"), InstallPlan.forTool("pg_dump", PackageManager.DNF, false).command());
        assertEquals(List.of("winget", "install", "--exact", "--id", "MongoDB.DatabaseTools"), InstallPlan.forTool("mongodump", PackageManager.WINGET, false).command());
    }

    @Test
    void whereARepoIsNeededItPointsToDocsInstead() {
        InstallPlan plan = InstallPlan.forTool("mongodump", PackageManager.APT, false);
        assertFalse(plan.canRun());
        assertTrue(plan.docsUrl().contains("mongodb.com"));
        assertFalse(InstallPlan.forTool("pg_dump", PackageManager.NONE, false).canRun());
    }

    /** Builds an installer where running the install "creates" the tool. */
    private ToolInstaller installer(boolean interactive, boolean answer, List<List<String>> ran, Path tool) {
        return new ToolInstaller(new ToolChecker(OperatingSystem.LINUX, dir.toString(), List.of()), PackageManager.APT, false, interactive,
                question -> answer,
                command -> {
                    ran.add(command);
                    executable(tool);
                    return 0;
                });
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void installsOnlyAfterAYes() {
        List<List<String>> ran = new ArrayList<>();
        Path tool = dir.resolve("pg_dump");
        assertEquals(tool, installer(true, true, ran, tool).require("pg_dump"));
        assertEquals(1, ran.size());
    }

    @Test
    void aNoMeansNothingRuns() {
        List<List<String>> ran = new ArrayList<>();
        ToolMissingException e = assertThrows(ToolMissingException.class, () -> installer(true, false, ran, dir.resolve("pg_dump")).require("pg_dump"));
        assertTrue(ran.isEmpty());
        assertTrue(e.getMessage().contains("apt-get install"), e.getMessage());
    }

    @Test
    void nonInteractiveNeverPromptsOrInstalls() {
        List<List<String>> ran = new ArrayList<>();
        ToolInstaller installer = new ToolInstaller(new ToolChecker(OperatingSystem.LINUX, dir.toString(), List.of()), PackageManager.APT, false, false,
                question -> {
                    throw new AssertionError("must not prompt");
                },
                command -> {
                    ran.add(command);
                    return 0;
                });
        assertThrows(ToolMissingException.class, () -> installer.require("pg_dump"));
        assertTrue(ran.isEmpty());
    }

    @Test
    void failedOrIneffectiveInstallIsReported() {
        ToolChecker checker = new ToolChecker(OperatingSystem.LINUX, dir.toString(), List.of());
        ToolInstaller failing = new ToolInstaller(checker, PackageManager.APT, false, true, q -> true, c -> 100);
        assertTrue(assertThrows(ToolMissingException.class, () -> failing.require("psql")).getMessage().contains("exit code 100"));
        ToolInstaller ineffective = new ToolInstaller(checker, PackageManager.APT, false, true, q -> true, c -> 0);
        assertTrue(assertThrows(ToolMissingException.class, () -> ineffective.require("psql")).getMessage().contains("PATH"));
    }
}
