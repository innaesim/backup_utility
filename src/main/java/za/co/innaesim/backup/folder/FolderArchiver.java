package za.co.innaesim.backup.folder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;

/**
 * Writes folders to a tar stream and extracts them again, without any external tools.
 *
 * Each folder goes under its own top-level entry named after it, so {@code /srv/uploads} becomes
 * {@code uploads/...}. Symlinks are stored as links and never followed. Modification times are
 * kept, and so are Unix permissions where the file system has them.
 */
public final class FolderArchiver {
    /** The job type, as written in config files. */
    public static final String TYPE = "folder";
    /** The extension of folder backups, before any {@code .gz}. */
    public static final String EXTENSION = ".tar";

    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final int BUFFER = 64 * 1024;

    /**
     * What writing an archive did.
     *
     * @param files   regular files and symlinks stored
     * @param changed files whose size changed while they were being read; stored at their original size
     * @param skipped special files, such as sockets and FIFOs, that a tar can't usefully hold
     */
    public record Result(int files, List<String> changed, List<String> skipped) {}

    /**
     * What extracting an archive did.
     *
     * @param files   regular files and symlinks restored
     * @param skipped entries that couldn't be restored on this system, such as symlinks on Windows without the privilege
     */
    public record Extracted(int files, List<String> skipped) {}

    private FolderArchiver() {}

    /**
     * Checks whether {@code type} is the folder job type, ignoring case.
     *
     * @param type a job type, possibly null
     * @return whether it is {@code folder}
     */
    public static boolean isFolder(String type) {
        return TYPE.equalsIgnoreCase(type);
    }

    /**
     * Writes folders to a tar stream. The stream is finished but not closed.
     *
     * @param roots    the folders to back up; their names must differ
     * @param excludes what to leave out
     * @param skip     folders never to enter, such as the backup's own output folder
     * @param out      where the tar goes
     * @return what was stored
     * @throws IOException if a folder is missing or anything in it can't be read
     */
    public static Result write(List<Path> roots, Excludes excludes, Set<Path> skip, OutputStream out) throws IOException {
        Set<String> tops = new HashSet<>();
        Set<Path> skipReal = new HashSet<>();
        for (Path path : skip) {
            try {
                skipReal.add(path.toRealPath());
            } catch (IOException e) {
                // Doesn't exist, so there's nothing to skip.
            }
        }

        TarArchiveOutputStream tar = new TarArchiveOutputStream(out, BUFFER, StandardCharsets.UTF_8.name());
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        tar.setAddPaxHeadersForNonAsciiNames(true);

        Writer writer = new Writer(tar, excludes, skipReal);
        for (Path root : roots) {
            String top = topName(root);
            if (!tops.add(top)) throw new IOException("Two folders are both named '" + top + "'; their contents would clash in the archive");
            Path real;
            try {
                real = root.toRealPath();
            } catch (NoSuchFileException e) {
                throw new IOException("Folder not found: " + root);
            }
            if (!Files.isDirectory(real)) throw new IOException("Not a folder: " + root);
            writer.walk(real, top);
        }
        tar.finish();
        tar.flush();
        return new Result(writer.files, List.copyOf(writer.changed), List.copyOf(writer.skipped));
    }

    /**
     * Gives the top-level archive name for a folder: its own name, or {@code root} for a file system root.
     *
     * @param folder a folder to back up
     * @return the name its contents go under
     */
    public static String topName(Path folder) {
        Path name = folder.toAbsolutePath().normalize().getFileName();
        return name == null ? "root" : name.toString();
    }

    private static final class Writer extends SimpleFileVisitor<Path> {
        private final TarArchiveOutputStream tar;
        private final Excludes excludes;
        private final Set<Path> skip;
        private final byte[] buffer = new byte[BUFFER];
        private final List<String> changed = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();
        private int files;
        private Path root;
        private String top;

        Writer(TarArchiveOutputStream tar, Excludes excludes, Set<Path> skip) {
            this.tar = tar;
            this.excludes = excludes;
            this.skip = skip;
        }

        void walk(Path root, String top) throws IOException {
            this.root = root;
            this.top = top;
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, this);
        }

        private boolean leaveOut(Path path) {
            return !path.equals(root) && (excludes.excludes(root.relativize(path)) || skip.contains(path));
        }

        private String entryName(Path path) {
            StringBuilder name = new StringBuilder(top);
            for (Path part : root.relativize(path)) {
                if (!part.toString().isEmpty()) name.append('/').append(part);
            }
            return name.toString();
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            if (leaveOut(dir)) return FileVisitResult.SKIP_SUBTREE;
            TarArchiveEntry entry = new TarArchiveEntry(entryName(dir) + "/");
            describe(entry, dir, attrs);
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (leaveOut(file)) return FileVisitResult.CONTINUE;
            if (attrs.isSymbolicLink()) {
                TarArchiveEntry entry = new TarArchiveEntry(entryName(file), TarConstants.LF_SYMLINK);
                entry.setLinkName(Files.readSymbolicLink(file).toString());
                describe(entry, file, attrs);
                tar.putArchiveEntry(entry);
                tar.closeArchiveEntry();
                files++;
            } else if (attrs.isRegularFile()) {
                storeFile(file, attrs);
                files++;
            } else {
                skipped.add(file.toString());
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
            throw new IOException("Can't read " + file + ": " + reason(e), e);
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
            if (e != null) throw new IOException("Can't read " + dir + ": " + reason(e), e);
            return FileVisitResult.CONTINUE;
        }

        /** The size goes in the header first, so a file that grows or shrinks meanwhile is cut or padded to fit. */
        private void storeFile(Path file, BasicFileAttributes attrs) throws IOException {
            InputStream in;
            try {
                in = Files.newInputStream(file);
            } catch (IOException e) {
                throw new IOException("Can't read " + file + ": " + reason(e), e);
            }
            try (in) {
                long size = attrs.size();
                TarArchiveEntry entry = new TarArchiveEntry(entryName(file));
                entry.setSize(size);
                describe(entry, file, attrs);
                tar.putArchiveEntry(entry);
                long remaining = size;
                while (remaining > 0) {
                    int read = read(in, file, buffer, (int) Math.min(buffer.length, remaining));
                    if (read < 0) break;
                    tar.write(buffer, 0, read);
                    remaining -= read;
                }
                boolean resized = remaining > 0 || read(in, file, buffer, 1) > 0;
                Arrays.fill(buffer, (byte) 0);
                while (remaining > 0) {
                    int pad = (int) Math.min(buffer.length, remaining);
                    tar.write(buffer, 0, pad);
                    remaining -= pad;
                }
                tar.closeArchiveEntry();
                if (resized) changed.add(file.toString());
            }
        }

        private static int read(InputStream in, Path file, byte[] buffer, int length) throws IOException {
            try {
                return in.read(buffer, 0, length);
            } catch (IOException e) {
                throw new IOException("Can't read " + file + ": " + reason(e), e);
            }
        }

        private static void describe(TarArchiveEntry entry, Path path, BasicFileAttributes attrs) {
            entry.setModTime(attrs.lastModifiedTime());
            if (isPosix(path)) {
                try {
                    int mode = toMode(Files.getPosixFilePermissions(path, NOFOLLOW));
                    entry.setMode((entry.getMode() & ~0777) | mode);
                } catch (IOException | UnsupportedOperationException e) {
                    // Keep the tar defaults.
                }
            }
        }
    }

    /**
     * Fails unless {@code target} is missing or an empty folder, so a restore can't overwrite anything.
     *
     * @param target where a restore would go
     * @throws IOException if it exists and isn't an empty folder
     */
    public static void requireEmptyTarget(Path target) throws IOException {
        if (!Files.exists(target, NOFOLLOW)) return;
        if (!Files.isDirectory(target, NOFOLLOW)) throw new IOException(target + " exists and isn't a folder");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
            if (stream.iterator().hasNext()) {
                throw new IOException(target + " isn't empty. Restore into a new or empty folder, then copy back what you need");
            }
        }
    }

    /**
     * Extracts a tar into a missing or empty folder. Nothing outside it is ever written, and no file is
     * overwritten: entries that would escape it, or write through a symlink, stop the restore.
     *
     * @param in     the tar stream; not closed
     * @param target the folder to extract into
     * @return what was restored
     * @throws IOException if the target isn't empty, the archive is unsafe or damaged, or a file can't be written
     */
    public static Extracted extract(InputStream in, Path target) throws IOException {
        requireEmptyTarget(target);
        Files.createDirectories(target);
        Path root = target.toRealPath();
        boolean posix = isPosix(root);
        List<String> skipped = new ArrayList<>();
        List<TarArchiveEntry> directories = new ArrayList<>();
        List<Path> directoryPaths = new ArrayList<>();
        int files = 0;

        try (TarArchiveInputStream tar = new TarArchiveInputStream(in, StandardCharsets.UTF_8.name())) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                List<String> parts = safeParts(entry.getName());
                if (parts.isEmpty()) continue;
                Path dest = root;
                for (String part : parts) dest = dest.resolve(part);
                if (!dest.normalize().startsWith(root)) throw new IOException("Archive entry '" + entry.getName() + "' points outside the restore folder");

                if (entry.isDirectory()) {
                    makeDirectories(root, parts);
                    directories.add(entry);
                    directoryPaths.add(dest);
                    continue;
                }
                makeDirectories(root, parts.subList(0, parts.size() - 1));
                if (entry.isSymbolicLink()) {
                    try {
                        Files.createSymbolicLink(dest, Path.of(entry.getLinkName()));
                        files++;
                    } catch (UnsupportedOperationException | FileSystemException e) {
                        if (Files.exists(dest, NOFOLLOW)) throw new IOException("Archive has '" + entry.getName() + "' more than once");
                        skipped.add(entry.getName() + " (symlink to " + entry.getLinkName() + ": " + reason(e) + ")");
                    }
                } else if (entry.isFile()) {
                    try (OutputStream out = Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        tar.transferTo(out);
                    } catch (java.nio.file.FileAlreadyExistsException e) {
                        throw new IOException("Archive has '" + entry.getName() + "' more than once");
                    }
                    Files.setLastModifiedTime(dest, FileTime.from(entry.getModTime().toInstant()));
                    if (posix) Files.setPosixFilePermissions(dest, fromMode(entry.getMode()));
                    files++;
                } else {
                    skipped.add(entry.getName() + " (not a file, folder or symlink)");
                }
            }
        }
        // Last, and deepest first, so a read-only folder doesn't block what goes inside it.
        for (int i = directories.size() - 1; i >= 0; i--) {
            Path dir = directoryPaths.get(i);
            Files.setLastModifiedTime(dir, FileTime.from(directories.get(i).getModTime().toInstant()));
            if (posix) Files.setPosixFilePermissions(dir, fromMode(directories.get(i).getMode()));
        }
        return new Extracted(files, List.copyOf(skipped));
    }

    /** Splits an entry name, refusing anything that could reach outside the restore folder. */
    private static List<String> safeParts(String name) throws IOException {
        if (name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("Archive entry '" + name + "' is an absolute path");
        }
        boolean windows = java.io.File.separatorChar == '\\';
        List<String> parts = new ArrayList<>();
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new IOException("Archive entry '" + name + "' contains '..'");
            if (windows && (part.contains("\\") || part.contains(":"))) {
                throw new IOException("Archive entry '" + name + "' can't be restored on Windows");
            }
            parts.add(part);
        }
        return parts;
    }

    /** Creates folders one level at a time, refusing to go through a symlink. */
    private static void makeDirectories(Path root, List<String> parts) throws IOException {
        Path current = root;
        for (String part : parts) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Archive would write through the symlink " + current);
            if (!Files.exists(current, NOFOLLOW)) {
                Files.createDirectory(current);
            } else if (!Files.isDirectory(current, NOFOLLOW)) {
                throw new IOException("Archive needs " + current + " to be a folder, but it's a file");
            }
        }
    }

    private static boolean isPosix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static final PosixFilePermission[] BITS = {
        PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ,
    };

    static int toMode(Set<PosixFilePermission> permissions) {
        int mode = 0;
        for (int i = 0; i < BITS.length; i++) {
            if (permissions.contains(BITS[i])) mode |= 1 << i;
        }
        return mode;
    }

    static Set<PosixFilePermission> fromMode(int mode) {
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        for (int i = 0; i < BITS.length; i++) {
            if ((mode & (1 << i)) != 0) permissions.add(BITS[i]);
        }
        return permissions;
    }

    private static String reason(Exception e) {
        if (e instanceof java.nio.file.AccessDeniedException) return "permission denied";
        if (e instanceof NoSuchFileException) return "no longer exists";
        if (e instanceof FileSystemException fse && fse.getReason() != null) return fse.getReason();
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
