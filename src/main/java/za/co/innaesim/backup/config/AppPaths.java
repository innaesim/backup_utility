package za.co.innaesim.backup.config;

import java.nio.file.Path;

/**
 * Where the utility keeps its files. Must not log: Main calls this before log4j starts,
 * because the log file location depends on the home directory.
 */
public final class AppPaths {
    /** System property that overrides the home directory. */
    public static final String HOME_PROPERTY = "backup.utility.home";

    private AppPaths() {}

    /**
     * Finds the home directory: the system property, then {@code BACKUP_UTILITY_HOME}, then {@code ~/.backup-utility}.
     *
     * @return the absolute home directory
     */
    public static Path home() {
        String property = System.getProperty(HOME_PROPERTY);
        if (property != null && !property.isBlank()) return Path.of(property).toAbsolutePath();
        String env = System.getenv("BACKUP_UTILITY_HOME");
        if (env != null && !env.isBlank()) return Path.of(env).toAbsolutePath();
        return Path.of(System.getProperty("user.home"), ".backup-utility");
    }

    /**
     * Finds the config file used when none is named.
     *
     * @return {@code config.yaml} in the home directory
     */
    public static Path defaultConfig() { return home().resolve("config.yaml"); }
    /**
     * Finds where {@code schedule add} keeps its files.
     *
     * @return {@code schedules.d} in the home directory
     */
    public static Path schedulesDir() { return home().resolve("schedules.d"); }
    /**
     * Finds where job locks are kept.
     *
     * @return {@code locks} in the home directory
     */
    public static Path locksDir() { return home().resolve("locks"); }
    /**
     * Finds the file that supplies {@code ${VAR}} values.
     *
     * @return {@code env} in the home directory
     */
    public static Path envFile() { return home().resolve("env"); }

    /**
     * Expands a leading {@code ~} to the user's home directory.
     *
     * @param path a path, or null
     * @return the expanded path, or null if {@code path} was null
     */
    public static String expandTilde(String path) {
        if (path == null) return null;
        if (path.equals("~")) return System.getProperty("user.home");
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
