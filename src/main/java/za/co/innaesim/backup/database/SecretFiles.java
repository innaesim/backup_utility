package za.co.innaesim.backup.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Owner-only temporary files for passing passwords to tools, so they never appear in a process
 * listing. Everything created here is deleted on close.
 */
public final class SecretFiles implements AutoCloseable {
    private final List<Path> created = new ArrayList<>();

    /** Creates an empty holder. */
    public SecretFiles() {}

    /**
     * Creates an owner-only temporary file.
     *
     * @param content what to write to the file
     * @return the new file
     * @throws IOException if the file can't be created or restricted
     */
    public Path create(String content) throws IOException {
        Path file;
        if (Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir"))).supportsFileAttributeView("posix")) {
            file = Files.createTempFile("backup-utility-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            file = Files.createTempFile("backup-utility-", ".tmp");
            restrictAcl(file);
        }
        created.add(file);
        Files.writeString(file, content);
        return file;
    }

    /**
     * Escapes a value as a double-quoted string for a MySQL option file or YAML file.
     *
     * @param value the raw value
     * @return the value, quoted and escaped
     */
    public static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }

    private static void restrictAcl(Path file) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (view == null) throw new IOException("Can't restrict access to " + file + " on this file system");
        AclEntry ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(view.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        view.setAcl(List.of(ownerOnly));
    }

    @Override
    public void close() {
        for (Path file : created) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                file.toFile().deleteOnExit();
            }
        }
        created.clear();
    }
}
