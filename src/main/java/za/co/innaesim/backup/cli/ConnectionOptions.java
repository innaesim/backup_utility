package za.co.innaesim.backup.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import za.co.innaesim.backup.database.DatabaseDriver;
import za.co.innaesim.backup.config.AppPaths;
import za.co.innaesim.backup.database.Drivers;
import za.co.innaesim.backup.folder.FolderArchiver;
import za.co.innaesim.backup.model.AppConfig;
import za.co.innaesim.backup.model.BackupSettings;
import za.co.innaesim.backup.model.JobConfig;
import za.co.innaesim.backup.model.RetentionConfig;
import za.co.innaesim.backup.platform.Prompter;

import picocli.CommandLine.Option;

/** Describes a job on the command line. There's deliberately no --password: it would show up in 'ps' and shell history. */
class ConnectionOptions {
    @Option(names = "--type", description = "postgresql, mysql, mongodb or folder.")
    String type;

    @Option(names = "--path", paramLabel = "FOLDER", description = "Folder jobs: a folder to back up. Repeatable.")
    List<String> paths = new ArrayList<>();

    @Option(names = "--exclude", paramLabel = "GLOB", description = "Folder jobs: leave out matching files and folders, e.g. node_modules or '*.tmp'. Repeatable.")
    List<String> excludes = new ArrayList<>();

    @Option(names = "--host", defaultValue = "localhost", description = "Database host. Default: ${DEFAULT-VALUE}.")
    String host;

    @Option(names = "--port", description = "Database port. Default: the database's standard port.")
    Integer port;

    @Option(names = "--db", description = "Database name.")
    String db;

    @Option(names = "--user", description = "Database user.")
    String user;

    @Option(names = "--password-env", paramLabel = "VAR", description = "Environment variable holding the password.")
    String passwordEnv;

    @Option(names = "--ask-password", description = "Prompt for the password.")
    boolean askPassword;

    @Option(names = "--auth-db", description = "MongoDB authentication database. Default: admin.")
    String authDb;

    @Option(names = "--output", defaultValue = "./backups", description = "Output directory. Default: ${DEFAULT-VALUE}.")
    String output;

    @Option(names = "--compress", description = "Compress the backup with gzip.")
    boolean compress;

    @Option(names = "--file-pattern", description = "File name pattern using {db} (the database or job name), {date} and {ext}.")
    String filePattern;

    @Option(names = "--destination", paramLabel = "NAME", description = "Destination from the config file to upload to. Repeatable.")
    List<String> destinations = new ArrayList<>();

    @Option(names = "--retention-days", description = "Delete backups older than this many days.")
    Integer retentionDays;

    @Option(names = "--keep-last", description = "Always keep this many of the newest backups.")
    Integer keepLast;

    boolean describesJob() {
        return type != null;
    }

    boolean isFolder() {
        return FolderArchiver.isFolder(type);
    }

    /** Refuses options that mean nothing for the job type, rather than silently ignoring them. */
    void checkOptionsForType() {
        if (isFolder()) {
            if (paths.isEmpty()) throw new IllegalArgumentException("--type folder needs at least one --path");
            List<String> unused = new ArrayList<>();
            if (db != null) unused.add("--db");
            if (port != null) unused.add("--port");
            if (user != null) unused.add("--user");
            if (passwordEnv != null) unused.add("--password-env");
            if (askPassword) unused.add("--ask-password");
            if (authDb != null) unused.add("--auth-db");
            if (!unused.isEmpty()) throw new IllegalArgumentException(String.join(", ", unused) + " can't be used with --type folder");
        } else if (!paths.isEmpty() || !excludes.isEmpty()) {
            throw new IllegalArgumentException("--path and --exclude are for --type folder");
        }
    }

    /** Folders to back up, made absolute so they still mean the same thing from the daemon. */
    List<String> absolutePaths() {
        return paths.stream().map(p -> Path.of(AppPaths.expandTilde(p)).toAbsolutePath().normalize().toString()).toList();
    }

    /**
     * Names an ad-hoc folder job after its first folder, keeping only characters that are safe in file names.
     *
     * @param folder the first folder being backed up
     * @return a usable job name
     */
    static String folderJobName(String folder) {
        String name = FolderArchiver.topName(Path.of(folder)).replaceAll("[^A-Za-z0-9._-]", "_").replaceFirst("^[.-]+", "");
        return name.isEmpty() ? "folder" : name;
    }

    JobConfig toJob(BackupSettings settings) {
        checkOptionsForType();
        JobConfig job = isFolder() ? folderJob() : databaseJob();
        for (String destination : destinations) {
            if (!settings.getDestinations().containsKey(destination)) {
                throw new IllegalArgumentException("No destination named '" + destination + "' in the config file");
            }
        }
        job.setDestinations(destinations);
        job.setRetention(retention());
        CliSupport.validateAdHoc(job);
        return job;
    }

    private JobConfig folderJob() {
        AppConfig config = new AppConfig();
        config.setType(FolderArchiver.TYPE);
        config.setPaths(absolutePaths());
        config.setExcludes(List.copyOf(excludes));
        config.setName(folderJobName(config.getPaths().get(0)));
        config.setOutputDir(output);
        config.setCompress(compress);
        if (filePattern != null) config.setFilepattern(filePattern);
        JobConfig job = new JobConfig();
        job.setName(config.getName());
        job.setDatabase(config);
        return job;
    }

    private JobConfig databaseJob() {
        if (db == null) throw new IllegalArgumentException("--db is required with --type");
        DatabaseDriver driver = Drivers.forType(type);
        AppConfig config = new AppConfig();
        config.setType(type.toLowerCase());
        config.setHost(host);
        config.setPort(port != null ? port : driver.defaultPort());
        config.setName(db);
        config.setUser(user);
        config.setAuthDb(authDb);
        config.setOutputDir(output);
        config.setCompress(compress);
        if (filePattern != null) config.setFilepattern(filePattern);
        if (askPassword) {
            config.setPassword(Prompter.password("Enter password:"));
        } else if (passwordEnv != null) {
            String value = System.getenv(passwordEnv);
            if (value == null) throw new IllegalArgumentException("Environment variable " + passwordEnv + " isn't set");
            config.setPassword(value);
        }

        JobConfig job = new JobConfig();
        job.setName(db);
        job.setDatabase(config);
        return job;
    }

    RetentionConfig retention() {
        if (retentionDays == null && keepLast == null) return null;
        if ((retentionDays != null && retentionDays < 1) || (keepLast != null && keepLast < 1)) {
            throw new IllegalArgumentException("--retention-days and --keep-last must be at least 1");
        }
        return new RetentionConfig(retentionDays, keepLast);
    }
}
