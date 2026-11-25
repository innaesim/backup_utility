package za.co.innaesim.backup.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import za.co.innaesim.backup.config.AppPaths;
import za.co.innaesim.backup.config.ConfigException;
import za.co.innaesim.backup.config.ConfigLoader;
import za.co.innaesim.backup.model.BackupSettings;
import za.co.innaesim.backup.model.JobConfig;
import za.co.innaesim.backup.schedule.CronSchedule;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Manages schedules in schedules.d, one file per job, so a hand-written config file and its
 * comments are never rewritten. The daemon notices changes within a minute.
 */
@Command(name = "schedule", mixinStandardHelpOptions = true,
        description = "Add, list and remove scheduled backups. Schedules run while the daemon is running.",
        subcommands = {ScheduleCommand.Add.class, ScheduleCommand.ListSchedules.class, ScheduleCommand.Remove.class})
class ScheduleCommand {

    /** The {@code schedule add} command. */
    @Command(name = "add", mixinStandardHelpOptions = true,
            description = "Schedule an existing job (--job), or define a new one (--name with --type and --db, or --type folder and --path).")
    static class Add implements Callable<Integer> {
        @Option(names = {"-c", "--config"}, description = "Config file. Default: ~/.backup-utility/config.yaml.")
        Path config;

        @Option(names = "--cron", required = true, description = "5-field cron expression, e.g. \"0 2 * * *\" for 2am daily. Quote it.")
        String cron;

        @Option(names = {"-j", "--job"}, description = "Existing job to schedule.")
        String existingJob;

        @Option(names = "--name", description = "Name for a new job.")
        String newJob;

        @Mixin
        ConnectionOptions connection;

        @Override
        public Integer call() throws IOException {
            if ((existingJob == null) == (newJob == null)) throw new IllegalArgumentException("Choose one of --job or --name");
            if (connection.askPassword) {
                throw new IllegalArgumentException("A schedule runs unattended, so it can't prompt. Use --password-env VAR");
            }
            CronSchedule.parse(cron);

            Map<String, Object> settings = new LinkedHashMap<>();
            String name;
            if (existingJob != null) {
                if (connection.describesJob()) throw new IllegalArgumentException("--job schedules an existing job; don't combine it with --type");
                name = existingJob;
                CliSupport.requireJob(ConfigLoader.load(config), name);
            } else {
                if (!connection.describesJob() || (!connection.isFolder() && connection.db == null)) {
                    throw new IllegalArgumentException("--name needs --type and --db, or --type folder and --path");
                }
                connection.checkOptionsForType();
                name = newJob;
                settings.putAll(jobSettings());
            }
            if (!ConfigLoader.SAFE_NAME.matcher(name).matches()) throw new IllegalArgumentException("'" + name + "' isn't a usable job name");
            settings.put("schedule", cron);

            write(name, settings);
            System.out.println("Scheduled job '" + name + "': " + cron);
            if (connection.passwordEnv != null) {
                System.out.println("The daemon needs " + connection.passwordEnv + " too. Services don't inherit your shell's environment, "
                        + "so add it to " + AppPaths.envFile() + " (chmod 600).");
            }
            System.out.println("Schedules only run while the daemon is running. See: backup-utility daemon, or backup-utility service enable");
            return 0;
        }

        private Map<String, Object> jobSettings() {
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("type", connection.type.toLowerCase());
            if (connection.isFolder()) {
                job.put("paths", connection.absolutePaths());
                if (!connection.excludes.isEmpty()) job.put("exclude", List.copyOf(connection.excludes));
                commonSettings(job);
                return job;
            }
            job.put("host", connection.host);
            if (connection.port != null) job.put("port", connection.port);
            job.put("name", connection.db);
            if (connection.user != null) job.put("user", connection.user);
            // Only a reference to the variable is stored, never the password itself.
            if (connection.passwordEnv != null) job.put("password", "${" + connection.passwordEnv + "}");
            if (connection.authDb != null) job.put("auth_db", connection.authDb);
            commonSettings(job);
            return job;
        }

        private void commonSettings(Map<String, Object> job) {
            // Absolute, since the daemon's working directory won't be this one.
            job.put("output_dir", Path.of(connection.output).toAbsolutePath().normalize().toString());
            job.put("compress", connection.compress);
            if (connection.filePattern != null) job.put("file_pattern", connection.filePattern);
            if (!connection.destinations.isEmpty()) job.put("destinations", List.copyOf(connection.destinations));
            if (connection.retention() != null) {
                Map<String, Object> retention = new LinkedHashMap<>();
                if (connection.retentionDays != null) retention.put("days", connection.retentionDays);
                if (connection.keepLast != null) retention.put("keep_last", connection.keepLast);
                job.put("retention", retention);
            }
        }

        /** Writes the file, then validates the merged config and puts things back if it's now invalid. */
        private void write(String name, Map<String, Object> settings) throws IOException {
            Path directory = AppPaths.schedulesDir();
            Files.createDirectories(directory);
            Path file = directory.resolve(name + ".yaml");
            String previous = Files.exists(file) ? Files.readString(file) : null;

            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            String content = new Yaml(options).dump(Map.of("jobs", Map.of(name, settings)));
            if (previous == null && file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            Files.writeString(file, content);
            try {
                ConfigLoader.load(config);
            } catch (ConfigException e) {
                if (previous == null) Files.delete(file);
                else Files.writeString(file, previous);
                throw e;
            }
        }
    }

    /** The {@code schedule list} command. */
    @Command(name = "list", mixinStandardHelpOptions = true, description = "Show scheduled jobs and their next run.")
    static class ListSchedules implements Callable<Integer> {
        @Option(names = {"-c", "--config"}, description = "Config file. Default: ~/.backup-utility/config.yaml.")
        Path config;

        @Override
        public Integer call() {
            BackupSettings settings = ConfigLoader.load(config);
            boolean any = false;
            for (JobConfig job : settings.getJobs().values()) {
                if (job.getSchedule() == null) continue;
                any = true;
                String next = CronSchedule.parse(job.getSchedule()).nextAfter(ZonedDateTime.now()).map(Object::toString).orElse("never");
                System.out.println(job.getName() + "  [" + job.getSchedule() + "]  next: " + next);
            }
            if (!any) System.out.println("No scheduled jobs.");
            return 0;
        }
    }

    /** The {@code schedule remove} command. */
    @Command(name = "remove", mixinStandardHelpOptions = true, description = "Remove a schedule added with 'schedule add'.")
    static class Remove implements Callable<Integer> {
        @Parameters(paramLabel = "JOB", description = "Job name.")
        String name;

        @Override
        public Integer call() throws IOException {
            if (!ConfigLoader.SAFE_NAME.matcher(name).matches()) throw new IllegalArgumentException("'" + name + "' isn't a usable job name");
            if (!Files.deleteIfExists(AppPaths.schedulesDir().resolve(name + ".yaml"))) {
                throw new IllegalArgumentException("No schedule file for '" + name + "'. If its schedule is in your config file, edit it there.");
            }
            System.out.println("Removed schedule for '" + name + "'");
            return 0;
        }
    }
}
