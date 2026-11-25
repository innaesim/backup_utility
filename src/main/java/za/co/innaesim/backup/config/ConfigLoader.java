package za.co.innaesim.backup.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import za.co.innaesim.backup.database.DatabaseDriver;
import za.co.innaesim.backup.database.Drivers;
import za.co.innaesim.backup.folder.Excludes;
import za.co.innaesim.backup.folder.FolderArchiver;
import za.co.innaesim.backup.model.AppConfig;
import za.co.innaesim.backup.model.BackupSettings;
import za.co.innaesim.backup.model.DestinationConfig;
import za.co.innaesim.backup.model.JobConfig;
import za.co.innaesim.backup.model.RetentionConfig;
import za.co.innaesim.backup.retention.BackupNaming;
import za.co.innaesim.backup.schedule.CronSchedule;

import lombok.extern.slf4j.Slf4j;

/** Reads the YAML config plus schedules.d, expands ${VAR} and validates the result. */
@Slf4j
public final class ConfigLoader {
    /** Names that are safe to use in file names: no path separators, and no leading dot. */
    public static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");

    private final Function<String, String> env;
    private final List<String> problems = new ArrayList<>();

    private ConfigLoader(Function<String, String> env) {
        this.env = env;
    }

    /**
     * Loads settings using the standard locations. An explicit config path must exist; the default
     * one may be missing, so jobs can come from schedules.d alone.
     *
     * @param explicitConfig the config file to use, or null for the default one
     * @return the validated settings
     * @throws ConfigException if the configuration can't be read or isn't valid
     */
    public static BackupSettings load(Path explicitConfig) {
        Path config = explicitConfig != null ? explicitConfig : AppPaths.defaultConfig();
        if (Files.isRegularFile(AppPaths.envFile())) warnIfExposed(AppPaths.envFile());
        return load(config, explicitConfig != null, AppPaths.schedulesDir(), EnvResolver.system(AppPaths.envFile()));
    }

    /**
     * Loads settings from specific locations.
     *
     * @param config the config file
     * @param required whether a missing config file is an error
     * @param schedulesDir directory of schedule files merged over the config's jobs; may be missing
     * @param env looks up {@code ${VAR}} values, returning null for unknown names
     * @return the validated settings
     * @throws ConfigException if the configuration can't be read or isn't valid
     */
    public static BackupSettings load(Path config, boolean required, Path schedulesDir, Function<String, String> env) {
        return new ConfigLoader(env).read(config.toAbsolutePath(), required, schedulesDir);
    }

    private BackupSettings read(Path config, boolean required, Path schedulesDir) {
        Map<String, Object> root = new LinkedHashMap<>();
        if (Files.isRegularFile(config)) {
            warnIfExposed(config);
            root = parseFile(config);
        } else if (required) {
            throw new ConfigException("Config file not found: " + config);
        }

        Map<String, Object> jobs = new LinkedHashMap<>(asMap(root.get("jobs"), "jobs"));
        for (Path file : scheduleFiles(schedulesDir)) {
            Map<String, Object> overlay = parseFile(file);
            for (Map.Entry<String, Object> entry : asMap(overlay.get("jobs"), file + ": jobs").entrySet()) {
                Map<String, Object> merged = new LinkedHashMap<>(asMap(jobs.get(entry.getKey()), "jobs." + entry.getKey()));
                merged.putAll(asMap(entry.getValue(), file + ": jobs." + entry.getKey()));
                jobs.put(entry.getKey(), merged);
            }
        }
        for (String key : root.keySet()) {
            if (!Set.of("jobs", "destinations", "notifications").contains(key)) {
                problems.add("Unknown top-level setting '" + key + "' (expected jobs, destinations, notifications)");
            }
        }

        BackupSettings settings = new BackupSettings();
        Map<String, Object> destinations = asMap(root.get("destinations"), "destinations");
        destinations.forEach((name, value) -> settings.getDestinations().put(name, parseDestination(name, value)));
        parseNotifications(root.get("notifications"), settings);
        Path base = config.getParent();
        jobs.forEach((name, value) -> settings.getJobs().put(name, parseJob(name, value, base, settings)));
        checkCollisions(settings);

        if (!problems.isEmpty()) throw new ConfigException(problems);
        return settings;
    }

    private JobConfig parseJob(String name, Object value, Path base, BackupSettings settings) {
        JobConfig job = new JobConfig();
        job.setName(name);
        requireSafeName("Job", name);
        Section s = new Section("jobs." + name, asMap(value, "jobs." + name), env, problems);
        AppConfig db = job.getDatabase();

        db.setType(s.requiredString("type"));
        if (db.getType() != null) db.setType(db.getType().toLowerCase());
        if (FolderArchiver.isFolder(db.getType())) {
            parseFolder(s, db, name, base);
        } else {
            parseDatabase(s, db, name);
        }
        // Relative to the config file, because a service's working directory isn't predictable.
        String outputDir = AppPaths.expandTilde(s.string("output_dir", "./backups"));
        db.setOutputDir(base.resolve(outputDir).normalize().toString());
        db.setCompress(s.bool("compress", false));
        String pattern = s.string("file_pattern", db.getFilepattern());
        if (!pattern.contains("{date}")) {
            problems.add("jobs." + name + ".file_pattern must contain {date}, or each backup would overwrite the last");
        }
        if (pattern.contains("/") || pattern.contains("\\")) {
            problems.add("jobs." + name + ".file_pattern can't contain a path separator; use output_dir");
        }
        db.setFilepattern(pattern);

        String cron = s.string("schedule", null);
        if (cron != null) {
            try {
                CronSchedule.parse(cron);
                job.setSchedule(cron);
            } catch (IllegalArgumentException e) {
                problems.add("jobs." + name + ".schedule: " + e.getMessage());
            }
        }
        job.setDestinations(s.stringList("destinations"));
        for (String destination : job.getDestinations()) {
            if (!settings.getDestinations().containsKey(destination)) {
                problems.add("jobs." + name + ".destinations: no destination named '" + destination + "'");
            }
        }
        job.setRetention(parseRetention(s));
        s.rejectUnknownKeys();
        return job;
    }

    private void parseDatabase(Section s, AppConfig db, String name) {
        DatabaseDriver driver = null;
        if (db.getType() != null) {
            try {
                driver = Drivers.forType(db.getType());
            } catch (IllegalArgumentException e) {
                problems.add("jobs." + name + ".type: " + e.getMessage());
            }
        }
        db.setHost(s.string("host", "localhost"));
        Integer port = s.integer("port");
        db.setPort(port != null ? port : (driver != null ? driver.defaultPort() : null));
        db.setName(s.requiredString("name"));
        if (db.getName() != null && !SAFE_NAME.matcher(db.getName()).matches()) {
            problems.add("jobs." + name + ".name: '" + db.getName() + "' isn't a usable database name");
        }
        db.setUser(s.string("user", null));
        db.setPassword(s.string("password", null));
        db.setAuthDb(s.string("auth_db", null));
    }

    /** Connection settings aren't read here, so rejectUnknownKeys reports any that a folder job has. */
    private void parseFolder(Section s, AppConfig db, String name, Path base) {
        db.setName(s.string("name", name));
        if (!SAFE_NAME.matcher(db.getName()).matches()) {
            problems.add("jobs." + name + ".name: '" + db.getName() + "' may only use letters, digits, '.', '_' and '-'");
        }
        List<String> paths = new ArrayList<>();
        Map<String, String> tops = new HashMap<>();
        for (String path : s.stringList("paths")) {
            if (path.isBlank()) {
                problems.add("jobs." + name + ".paths has an empty entry");
                continue;
            }
            // Relative to the config file, like output_dir.
            Path resolved = base.resolve(AppPaths.expandTilde(path)).normalize();
            String top = FolderArchiver.topName(resolved);
            String other = tops.putIfAbsent(top, path);
            if (other != null) {
                problems.add("jobs." + name + ".paths: '" + other + "' and '" + path + "' are both named '" + top
                        + "', so they'd clash in the archive. Back them up in separate jobs");
            }
            paths.add(resolved.toString());
        }
        if (paths.isEmpty() && !s.has("paths")) problems.add("jobs." + name + ".paths is required for a folder job");
        else if (paths.isEmpty()) problems.add("jobs." + name + ".paths must list at least one folder");
        db.setPaths(paths);

        List<String> excludes = s.stringList("exclude");
        try {
            Excludes.compile(excludes);
        } catch (IllegalArgumentException e) {
            problems.add("jobs." + name + ".exclude: " + e.getMessage());
        }
        db.setExcludes(excludes);
    }

    private RetentionConfig parseRetention(Section job) {
        Section s = job.child("retention");
        if (s == null) return null;
        Integer days = s.integer("days");
        Integer keepLast = s.integer("keep_last");
        s.rejectUnknownKeys();
        if (days == null && keepLast == null) problems.add(s.path() + " needs days and/or keep_last");
        if (days != null && days < 1) problems.add(s.path() + ".days must be at least 1");
        if (keepLast != null && keepLast < 1) problems.add(s.path() + ".keep_last must be at least 1");
        return new RetentionConfig(days, keepLast);
    }

    private DestinationConfig parseDestination(String name, Object value) {
        requireSafeName("Destination", name);
        Section s = new Section("destinations." + name, asMap(value, "destinations." + name), env, problems);
        DestinationConfig d = new DestinationConfig();
        d.setName(name);
        d.setType(s.requiredString("type"));
        String type = d.getType() == null ? "" : d.getType().toLowerCase();
        d.setType(type);
        switch (type) {
            case "ftp" -> {
                d.setHost(s.requiredString("host"));
                d.setPort(s.integer("port"));
                d.setUser(s.requiredString("user"));
                d.setPassword(s.requiredString("password"));
                d.setPath(s.string("path", "."));
                d.setTls(s.bool("tls", false));
            }
            case "sftp" -> {
                d.setHost(s.requiredString("host"));
                d.setPort(s.integer("port"));
                d.setUser(s.requiredString("user"));
                d.setPassword(s.string("password", null));
                d.setKeyFile(AppPaths.expandTilde(s.string("key_file", null)));
                d.setKeyPassphrase(s.string("key_passphrase", null));
                d.setKnownHosts(AppPaths.expandTilde(s.string("known_hosts", null)));
                d.setPath(s.string("path", "."));
                if (d.getPassword() == null && d.getKeyFile() == null) {
                    problems.add(s.path() + " needs key_file or password");
                }
            }
            case "s3" -> {
                d.setBucket(s.requiredString("bucket"));
                d.setRegion(s.string("region", "us-east-1"));
                d.setEndpoint(s.string("endpoint", null));
                d.setPrefix(s.string("prefix", ""));
                d.setAccessKey(s.string("access_key", null));
                d.setSecretKey(s.string("secret_key", null));
                if ((d.getAccessKey() == null) != (d.getSecretKey() == null)) {
                    problems.add(s.path() + " needs both access_key and secret_key, or neither");
                }
            }
            case "azure" -> {
                d.setContainer(s.requiredString("container"));
                d.setConnectionString(s.requiredString("connection_string"));
                d.setPrefix(s.string("prefix", ""));
            }
            case "" -> { /* already reported as missing */ }
            default -> problems.add(s.path() + ".type: '" + type + "' isn't supported (use ftp, sftp, s3 or azure)");
        }
        s.rejectUnknownKeys();
        return d;
    }

    private void parseNotifications(Object value, BackupSettings settings) {
        Section root = new Section("notifications", asMap(value, "notifications"), env, problems);
        Section slack = root.child("slack");
        root.rejectUnknownKeys();
        if (slack == null) return;
        String url = slack.requiredString("webhook_url");
        if (url != null && !url.startsWith("https://") && !url.startsWith("http://")) {
            problems.add(slack.path() + ".webhook_url must be an http(s) URL");
        }
        settings.setSlackWebhookUrl(url);
        if (slack.has("notify_on")) {
            Set<String> on = new LinkedHashSet<>(slack.stringList("notify_on"));
            for (String item : on) {
                if (!Set.of("success", "failure").contains(item)) {
                    problems.add(slack.path() + ".notify_on: '" + item + "' must be success or failure");
                }
            }
            settings.setSlackNotifyOn(on);
        }
        slack.rejectUnknownKeys();
    }

    /**
     * Retention deletes by filename, so two jobs producing the same names in the same place would
     * delete each other's backups. Refuse that configuration rather than lose data.
     */
    private void checkCollisions(BackupSettings settings) {
        Map<String, String> seen = new HashMap<>();
        for (JobConfig job : settings.getJobs().values()) {
            AppConfig db = job.getDatabase();
            if (db.getType() == null || db.getName() == null) continue;
            String names = db.getFilepattern().replace("{db}", db.getName()).replace("{ext}", extension(db));
            List<String> places = new ArrayList<>();
            places.add("directory " + db.getOutputDir());
            job.getDestinations().forEach(d -> places.add("destination " + d));
            for (String place : places) {
                String other = seen.putIfAbsent(place + "|" + names, job.getName());
                if (other != null) {
                    problems.add("Jobs '" + other + "' and '" + job.getName() + "' write the same file names to "
                            + place + ", so retention would delete each other's backups. Change output_dir or file_pattern.");
                }
            }
        }
    }

    private static String extension(AppConfig db) {
        try {
            return BackupNaming.fileExtension(db.getType());
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private void requireSafeName(String what, String name) {
        if (!SAFE_NAME.matcher(name).matches()) {
            problems.add(what + " name '" + name + "' may only use letters, digits, '.', '_' and '-'");
        }
    }

    private Map<String, Object> parseFile(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            if (parsed == null) return new LinkedHashMap<>();
            if (!(parsed instanceof Map)) throw new ConfigException(file + " must contain YAML settings, not a list or value");
            return asMap(parsed, file.toString());
        } catch (YAMLException e) {
            throw new ConfigException("Can't parse " + file + ": " + e.getMessage());
        } catch (IOException e) {
            throw new ConfigException("Can't read " + file + ": " + e.getMessage());
        }
    }

    private List<Path> scheduleFiles(Path dir) {
        Set<Path> files = new TreeSet<>();
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.yaml")) {
            stream.forEach(files::add);
        } catch (IOException e) {
            throw new ConfigException("Can't read " + dir + ": " + e.getMessage());
        }
        return new ArrayList<>(files);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value, String where) {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            ((Map<Object, Object>) value).forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        problems.add(where + " must be a group of settings");
        return new LinkedHashMap<>();
    }

    private static void warnIfExposed(Path file) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            if (permissions.contains(PosixFilePermission.GROUP_READ) || permissions.contains(PosixFilePermission.OTHERS_READ)) {
                log.warn("{} is readable by other users. If it holds secrets, run: chmod 600 {}", file, file);
            }
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("Can't check permissions on {}", file);
        }
    }
}
