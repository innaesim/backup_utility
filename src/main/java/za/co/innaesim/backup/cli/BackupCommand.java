package za.co.innaesim.backup.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import za.co.innaesim.backup.config.ConfigLoader;
import za.co.innaesim.backup.model.BackupSettings;
import za.co.innaesim.backup.model.JobConfig;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/** The {@code backup} command. */
@Command(name = "backup", mixinStandardHelpOptions = true,
        description = "Run a backup now: a configured job, all jobs, or one described by options.")
class BackupCommand implements Callable<Integer> {
    @Option(names = {"-c", "--config"}, description = "Config file. Default: ~/.backup-utility/config.yaml.")
    Path config;

    @Option(names = {"-j", "--job"}, description = "Job to run from the config file.")
    String jobName;

    @Option(names = "--all", description = "Run every job in the config file.")
    boolean all;

    @Mixin
    ConnectionOptions connection;

    @Override
    public Integer call() {
        int modes = (jobName != null ? 1 : 0) + (all ? 1 : 0) + (connection.describesJob() ? 1 : 0);
        if (modes != 1) throw new IllegalArgumentException("Choose one of --job NAME, --all, or --type with --db");

        BackupSettings settings = ConfigLoader.load(config);
        if (connection.describesJob()) return CliSupport.runBackup(connection.toJob(settings), settings, true);
        if (jobName != null) return CliSupport.runBackup(CliSupport.requireJob(settings, jobName), settings, true);

        if (settings.getJobs().isEmpty()) throw new IllegalArgumentException("The config file has no jobs");
        int exit = 0;
        for (JobConfig job : settings.getJobs().values()) {
            if (CliSupport.runBackup(job, settings, true) != 0) exit = 1; // one failure doesn't stop the rest
        }
        return exit;
    }
}
