package za.co.innaesim.backup.database;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import za.co.innaesim.backup.model.AppConfig;
import za.co.innaesim.backup.platform.ToolChecker;

/** The supported databases. */
public final class Drivers {
    /** Every supported database. */
    public static final List<DatabaseDriver> ALL = List.of(new Postgres(), new MySql(), new Mongo());

    private Drivers() {}

    /**
     * Finds the driver for a database type, ignoring case.
     *
     * @param type the type name, such as {@code mysql}
     * @return the matching driver
     * @throws IllegalArgumentException if the type isn't supported
     */
    public static DatabaseDriver forType(String type) {
        for (DatabaseDriver driver : ALL) {
            if (driver.type().equalsIgnoreCase(type)) return driver;
        }
        throw new IllegalArgumentException("Unsupported type '" + type + "' (use postgresql, mysql, mongodb or folder)");
    }

    private static boolean hasPassword(AppConfig db) {
        return db.getPassword() != null && !db.getPassword().isEmpty();
    }

    /** The password goes through PGPASSWORD; --no-password makes a wrong one fail rather than hang on a prompt. */
    static final class Postgres implements DatabaseDriver {
        @Override public String type() { return "postgresql"; }
        @Override public int defaultPort() { return 5432; }
        @Override public String backupTool() { return "pg_dump"; }
        @Override public String restoreTool() { return "psql"; }
        @Override public String fileExtension() { return ".sql"; }

        @Override
        public ProcessSpec backupCommand(AppConfig db, Path tool, SecretFiles secrets) {
            List<String> command = connection(tool, db);
            command.add(db.getName());
            return new ProcessSpec(command, environment(db));
        }

        @Override
        public ProcessSpec restoreCommand(AppConfig db, String sourceDb, Path tool, SecretFiles secrets) {
            List<String> command = connection(tool, db);
            command.addAll(List.of("-v", "ON_ERROR_STOP=1", "-d", db.getName()));
            return new ProcessSpec(command, environment(db));
        }

        private static List<String> connection(Path tool, AppConfig db) {
            List<String> command = new ArrayList<>(List.of(tool.toString(), "-h", db.getHost(), "-p", String.valueOf(db.getPort()), "--no-password"));
            if (db.getUser() != null) command.addAll(List.of("-U", db.getUser()));
            return command;
        }

        private static Map<String, String> environment(AppConfig db) {
            Map<String, String> environment = new HashMap<>();
            if (hasPassword(db)) environment.put("PGPASSWORD", db.getPassword());
            return environment;
        }
    }

    /** The password goes through an option file, which must be the first argument. */
    static final class MySql implements DatabaseDriver {
        @Override public String type() { return "mysql"; }
        @Override public int defaultPort() { return 3306; }
        @Override public String backupTool() { return "mysqldump"; }
        @Override public String restoreTool() { return "mysql"; }
        @Override public String fileExtension() { return ".sql"; }

        @Override
        public ProcessSpec backupCommand(AppConfig db, Path tool, SecretFiles secrets) throws IOException {
            List<String> command = connection(tool, db, secrets);
            // A consistent snapshot without locking InnoDB tables; routines aren't dumped by default.
            command.addAll(List.of("--single-transaction", "--routines"));
            // Otherwise a dump from a GTID server sets GTID_PURGED, which makes restoring it fail.
            // MariaDB's mysqldump doesn't have this option and would refuse to run.
            if (!ToolChecker.version(tool).contains("MariaDB")) command.add("--set-gtid-purged=OFF");
            command.add(db.getName());
            return new ProcessSpec(command, Map.of());
        }

        @Override
        public ProcessSpec restoreCommand(AppConfig db, String sourceDb, Path tool, SecretFiles secrets) throws IOException {
            List<String> command = connection(tool, db, secrets);
            command.add(db.getName());
            return new ProcessSpec(command, Map.of());
        }

        private static List<String> connection(Path tool, AppConfig db, SecretFiles secrets) throws IOException {
            List<String> command = new ArrayList<>();
            command.add(tool.toString());
            if (hasPassword(db)) {
                Path options = secrets.create("[client]\npassword=" + SecretFiles.quote(db.getPassword()) + "\n");
                command.add("--defaults-extra-file=" + options);
            }
            command.addAll(List.of("-h", db.getHost(), "-P", String.valueOf(db.getPort())));
            if (db.getUser() != null) command.addAll(List.of("-u", db.getUser()));
            return command;
        }
    }

    /** The password goes through a --config YAML file. Dumps use mongodump's archive format on stdout. */
    static final class Mongo implements DatabaseDriver {
        @Override public String type() { return "mongodb"; }
        @Override public int defaultPort() { return 27017; }
        @Override public String backupTool() { return "mongodump"; }
        @Override public String restoreTool() { return "mongorestore"; }
        @Override public String fileExtension() { return ".archive"; }

        @Override
        public ProcessSpec backupCommand(AppConfig db, Path tool, SecretFiles secrets) throws IOException {
            List<String> command = connection(tool, db, secrets);
            command.addAll(List.of("--db=" + db.getName(), "--archive"));
            return new ProcessSpec(command, Map.of());
        }

        @Override
        public ProcessSpec restoreCommand(AppConfig db, String sourceDb, Path tool, SecretFiles secrets) throws IOException {
            List<String> command = connection(tool, db, secrets);
            command.add("--archive");
            command.add("--nsInclude=" + sourceDb + ".*");
            if (!sourceDb.equals(db.getName())) {
                command.add("--nsFrom=" + sourceDb + ".*");
                command.add("--nsTo=" + db.getName() + ".*");
            }
            return new ProcessSpec(command, Map.of());
        }

        private static List<String> connection(Path tool, AppConfig db, SecretFiles secrets) throws IOException {
            List<String> command = new ArrayList<>(List.of(tool.toString(), "--host=" + db.getHost(), "--port=" + db.getPort()));
            if (db.getUser() != null) {
                command.add("--username=" + db.getUser());
                command.add("--authenticationDatabase=" + (db.getAuthDb() != null ? db.getAuthDb() : "admin"));
            }
            if (hasPassword(db)) {
                Path config = secrets.create("password: " + SecretFiles.quote(db.getPassword()) + "\n");
                command.add("--config=" + config);
            }
            return command;
        }
    }
}
