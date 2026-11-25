package za.co.innaesim.backup.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import za.co.innaesim.backup.model.DestinationConfig;
import za.co.innaesim.backup.platform.ToolMissingException;
import za.co.innaesim.backup.platform.ToolProvider;
import za.co.innaesim.backup.storage.RemoteStore;
import za.co.innaesim.backup.storage.StoreFactory;

/** Stub database tools (shell scripts) and an in-memory remote store. */
final class FakeTools {
    private FakeTools() {}

    /** Creates an executable script. It logs its args and PGPASSWORD to &lt;tool&gt;.log. */
    static Path script(Path dir, String tool, String body) throws IOException {
        Path file = dir.resolve(tool);
        Path log = dir.resolve(tool + ".log");
        Files.writeString(file, "#!/bin/sh\necho \"args: $*\" >> '" + log + "'\necho \"pgpassword: $PGPASSWORD\" >> '" + log + "'\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    static ToolProvider in(Path dir) {
        return tool -> {
            Path file = dir.resolve(tool);
            if (!Files.exists(file)) throw new ToolMissingException(tool + " isn't installed");
            return file;
        };
    }

    static final class MemoryStore implements RemoteStore {
        final Map<String, byte[]> files = new HashMap<>();
        final List<String> deleted = new ArrayList<>();
        int failuresLeft;
        int uploads;
        boolean truncate;

        @Override
        public void upload(Path file, String name) throws IOException {
            uploads++;
            if (failuresLeft > 0) {
                failuresLeft--;
                throw new IOException("network down, password=hunter2");
            }
            byte[] data = Files.readAllBytes(file);
            files.put(name, truncate ? new byte[1] : data);
        }

        @Override
        public List<RemoteFile> list() {
            List<RemoteFile> out = new ArrayList<>();
            files.forEach((name, data) -> out.add(new RemoteFile(name, data.length)));
            return out;
        }

        @Override
        public void download(String name, Path target) throws IOException {
            if (!files.containsKey(name)) throw new IOException("no such file " + name);
            Files.write(target, files.get(name));
        }

        @Override
        public void delete(String name) {
            files.remove(name);
            deleted.add(name);
        }

        @Override
        public void close() {
            // Nothing to release.
        }

        StoreFactory factory() {
            return (DestinationConfig d) -> this;
        }
    }
}
