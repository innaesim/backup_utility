package za.co.innaesim.backup.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * How to get a database's client tools on this machine: either a command to run, or a docs page
 * when a package needs a vendor repository we shouldn't configure on the user's behalf.
 *
 * @param command the program and arguments to run, or null when only docs are available
 * @param docsUrl where to read install instructions, or null when there's a command
 */
public record InstallPlan(List<String> command, String docsUrl) {
    private static final String POSTGRES_DOCS = "https://www.postgresql.org/download/";
    private static final String MYSQL_DOCS = "https://dev.mysql.com/downloads/";
    private static final String MONGO_DOCS = "https://www.mongodb.com/docs/database-tools/installation/";

    /**
     * Tells whether this plan has a command to run.
     *
     * @return true if there's a command, false if there's only a docs page
     */
    public boolean canRun() {
        return command != null;
    }

    /**
     * Works out how to install a database tool.
     *
     * @param tool the tool's name, such as {@code pg_dump}
     * @param manager the package manager available
     * @param root whether this is running as root, so {@code sudo} isn't needed
     * @return the plan
     * @throws IllegalArgumentException if the tool isn't a known database tool
     */
    public static InstallPlan forTool(String tool, PackageManager manager, boolean root) {
        String family = family(tool);
        return switch (manager) {
            case BREW -> switch (family) {
                case "postgresql" -> run("brew", "install", "libpq");
                case "mysql" -> run("brew", "install", "mysql-client");
                default -> run("brew", "install", "mongodb/brew/mongodb-database-tools");
            };
            case APT -> switch (family) {
                case "postgresql" -> elevated(root, "apt-get", "install", "-y", "postgresql-client");
                case "mysql" -> elevated(root, "apt-get", "install", "-y", "default-mysql-client");
                default -> docs(MONGO_DOCS);
            };
            case DNF, YUM -> switch (family) {
                case "postgresql" -> elevated(root, manager.executable(), "install", "-y", "postgresql");
                case "mysql" -> elevated(root, manager.executable(), "install", "-y", "mysql");
                default -> docs(MONGO_DOCS);
            };
            case WINGET -> switch (family) {
                case "postgresql" -> run("winget", "install", "--exact", "--id", "PostgreSQL.PostgreSQL.17");
                case "mysql" -> run("winget", "install", "--exact", "--id", "Oracle.MySQL");
                default -> run("winget", "install", "--exact", "--id", "MongoDB.DatabaseTools");
            };
            case NONE -> switch (family) {
                case "postgresql" -> docs(POSTGRES_DOCS);
                case "mysql" -> docs(MYSQL_DOCS);
                default -> docs(MONGO_DOCS);
            };
        };
    }

    private static String family(String tool) {
        return switch (tool) {
            case "pg_dump", "psql" -> "postgresql";
            case "mysqldump", "mysql" -> "mysql";
            case "mongodump", "mongorestore" -> "mongodb";
            default -> throw new IllegalArgumentException("Unknown tool " + tool);
        };
    }

    private static InstallPlan run(String... command) {
        return new InstallPlan(List.of(command), null);
    }

    private static InstallPlan elevated(boolean root, String... command) {
        List<String> full = new ArrayList<>();
        if (!root) full.add("sudo");
        full.addAll(List.of(command));
        return new InstallPlan(List.copyOf(full), null);
    }

    private static InstallPlan docs(String url) {
        return new InstallPlan(null, url);
    }
}
