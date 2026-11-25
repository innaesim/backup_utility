package za.co.innaesim.backup.folder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FolderArchiverTest {
    @TempDir
    Path source;
    @TempDir
    Path work;

    private Path restored() {
        return work.resolve("restored");
    }

    private static byte[] archive(List<Path> roots, List<String> excludes, Set<Path> skip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FolderArchiver.write(roots, Excludes.compile(excludes), skip, out);
        return out.toByteArray();
    }

    private FolderArchiver.Extracted extract(byte[] tar) throws IOException {
        return FolderArchiver.extract(new ByteArrayInputStream(tar), restored());
    }

    private static List<String> tree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(p -> !p.equals(root)).map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }

    private Path folder(String name) throws IOException {
        Path dir = source.resolve(name);
        Files.createDirectories(dir.resolve("sub/empty"));
        Files.writeString(dir.resolve("a.txt"), "alpha");
        Files.writeString(dir.resolve("sub/b.txt"), "bravo");
        return dir;
    }

    @Test
    void roundTripKeepsContentAndTimes() throws IOException {
        Path docs = folder("docs");
        FileTime time = FileTime.from(Instant.parse("2024-05-06T07:08:09Z"));
        Files.setLastModifiedTime(docs.resolve("a.txt"), time);

        extract(archive(List.of(docs), List.of(), Set.of()));

        assertEquals(List.of("docs", "docs/a.txt", "docs/sub", "docs/sub/b.txt", "docs/sub/empty"), tree(restored()));
        assertEquals("alpha", Files.readString(restored().resolve("docs/a.txt")));
        assertEquals("bravo", Files.readString(restored().resolve("docs/sub/b.txt")));
        assertEquals(time, Files.getLastModifiedTime(restored().resolve("docs/a.txt")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void keepsUnixPermissions() throws IOException {
        Path docs = folder("docs");
        Files.setPosixFilePermissions(docs.resolve("a.txt"), PosixFilePermissions.fromString("rwxr-x---"));
        Files.setPosixFilePermissions(docs.resolve("sub"), PosixFilePermissions.fromString("rwx------"));

        extract(archive(List.of(docs), List.of(), Set.of()));

        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(restored().resolve("docs/a.txt"))));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(restored().resolve("docs/sub"))));
    }

    @Test
    void eachFolderGoesUnderItsOwnName() throws IOException {
        Path docs = folder("docs");
        Path photos = folder("photos");
        FolderArchiver.Extracted extracted = extract(archive(List.of(docs, photos), List.of(), Set.of()));
        assertTrue(Files.exists(restored().resolve("docs/sub/b.txt")));
        assertTrue(Files.exists(restored().resolve("photos/sub/b.txt")));
        assertEquals(4, extracted.files());
    }

    @Test
    void foldersWithTheSameNameAreRefused() throws IOException {
        Path one = folder("x/data");
        Path two = folder("y/data");
        IOException e = assertThrows(IOException.class, () -> archive(List.of(one, two), List.of(), Set.of()));
        assertTrue(e.getMessage().contains("'data'"), e.getMessage());
    }

    @Test
    void excludesByNameAnywhereAndByRelativePath() throws IOException {
        Path app = folder("app");
        Files.createDirectories(app.resolve("node_modules/pkg"));
        Files.writeString(app.resolve("node_modules/pkg/index.js"), "x");
        Files.createDirectories(app.resolve("sub/node_modules"));
        Files.writeString(app.resolve("sub/scratch.tmp"), "x");
        Files.createDirectories(app.resolve("cache/deep"));
        Files.writeString(app.resolve("cache/deep/blob"), "x");

        extract(archive(List.of(app), List.of("node_modules", "*.tmp", "cache/**"), Set.of()));

        assertEquals(List.of("app", "app/a.txt", "app/cache", "app/sub", "app/sub/b.txt", "app/sub/empty"), tree(restored()));
    }

    @Test
    void skippedFolderIsLeftOut() throws IOException {
        Path docs = folder("docs");
        Path backups = docs.resolve("backups");
        Files.createDirectories(backups);
        Files.writeString(backups.resolve("docs_20240101_000000.tar"), "an older backup");

        extract(archive(List.of(docs), List.of(), Set.of(backups)));

        assertFalse(Files.exists(restored().resolve("docs/backups")));
        assertTrue(Files.exists(restored().resolve("docs/a.txt")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void symlinksAreStoredAsLinksNotFollowed() throws IOException {
        Path docs = folder("docs");
        Path outside = Files.writeString(source.resolve("secret.txt"), "not part of docs");
        Files.createSymbolicLink(docs.resolve("link"), outside);
        Files.createSymbolicLink(docs.resolve("relative"), Path.of("a.txt"));

        extract(archive(List.of(docs), List.of(), Set.of()));

        assertTrue(Files.isSymbolicLink(restored().resolve("docs/link")));
        assertEquals(outside, Files.readSymbolicLink(restored().resolve("docs/link")));
        assertEquals(Path.of("a.txt"), Files.readSymbolicLink(restored().resolve("docs/relative")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void unreadableFileFailsTheBackup() throws IOException {
        assumeFalse("root".equals(System.getProperty("user.name")), "root can read anything");
        Path docs = folder("docs");
        Path locked = docs.resolve("locked.txt");
        Files.writeString(locked, "private");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            IOException e = assertThrows(IOException.class, () -> archive(List.of(docs), List.of(), Set.of()));
            assertTrue(e.getMessage().contains("locked.txt"), e.getMessage());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void missingFolderFails() {
        IOException e = assertThrows(IOException.class, () -> archive(List.of(source.resolve("ghost")), List.of(), Set.of()));
        assertTrue(e.getMessage().startsWith("Folder not found"), e.getMessage());
    }

    @Test
    void extractRefusesANonEmptyFolder() throws IOException {
        byte[] tar = archive(List.of(folder("docs")), List.of(), Set.of());
        Files.createDirectories(restored());
        Files.writeString(restored().resolve("keep.txt"), "mine");
        IOException e = assertThrows(IOException.class, () -> extract(tar));
        assertTrue(e.getMessage().contains("isn't empty"), e.getMessage());
        assertEquals(List.of("keep.txt"), tree(restored()));
    }

    @Test
    void extractIntoAnEmptyFolderWorks() throws IOException {
        byte[] tar = archive(List.of(folder("docs")), List.of(), Set.of());
        Files.createDirectories(restored());
        extract(tar);
        assertTrue(Files.exists(restored().resolve("docs/a.txt")));
    }

    private static byte[] craft(TarArchiveEntry... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            for (TarArchiveEntry entry : entries) {
                byte[] body = entry.isFile() ? "pwned".getBytes(StandardCharsets.UTF_8) : new byte[0];
                if (entry.isFile()) entry.setSize(body.length);
                tar.putArchiveEntry(entry);
                tar.write(body);
                tar.closeArchiveEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void extractRefusesEntriesThatEscape() throws IOException {
        assertThrows(IOException.class, () -> extract(craft(new TarArchiveEntry("docs/../../evil.txt", true))));
        assertFalse(Files.exists(work.resolve("evil.txt")));
        Files.walk(restored()).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());

        assertThrows(IOException.class, () -> extract(craft(new TarArchiveEntry("/tmp/evil.txt", true))));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void extractRefusesToWriteThroughASymlink() throws IOException {
        Path outside = Files.createDirectories(work.resolve("outside"));
        TarArchiveEntry link = new TarArchiveEntry("docs/link", TarConstants.LF_SYMLINK);
        link.setLinkName(outside.toString());
        TarArchiveEntry through = new TarArchiveEntry("docs/link/evil.txt");

        IOException e = assertThrows(IOException.class, () -> extract(craft(link, through)));
        assertTrue(e.getMessage().contains("symlink"), e.getMessage());
        assertEquals(List.of(), tree(outside));
    }

    @Test
    void badExcludePatternsAreExplained() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Excludes.compile(List.of("/abs")))
                .getMessage().contains("slash"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Excludes.compile(List.of("[oops")))
                .getMessage().contains("isn't a valid glob"));
    }
}
