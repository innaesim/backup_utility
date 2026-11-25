package za.co.innaesim.backup.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import za.co.innaesim.backup.config.ConfigLoader;
import za.co.innaesim.backup.model.BackupSettings;
import za.co.innaesim.backup.model.JobConfig;
import za.co.innaesim.backup.platform.Prompter;
import za.co.innaesim.backup.platform.ToolInstaller;
import za.co.innaesim.backup.service.RestoreService;
import za.co.innaesim.backup.service.RunReport;
import za.co.innaesim.backup.storage.StoreFactory;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** The {@code restore} command. */
@Command(name = "restore", mixinStandardHelpOptions = true,
        description = "Restore a backup into a database, or a folder backup into a new or empty folder. "
                + "For databases, asks you to type the database name first, unless --yes is given.")
class RestoreCommand implements Callable<Integer> {
    /** Which backup to restore: exactly one of these must be given. */
    static class Which {
        @Option(names = "--latest", description = "Use the newest backup.")
        boolean latest;

        @Option(names = "--file", description = "Backup file, by name or path.")
        String file;
    }

    @Option(names = {"-c", "--config"}, description = "Config file. Default: ~/.backup-utility/config.yaml.")
    Path config;

    @Option(names = {"-j", "--job"}, required = true, description = "Job whose connection settings and backups to use.")
    String jobName;

    @ArgGroup(multiplicity = "1")
    Which which;

    @Option(names = "--from", paramLabel = "DESTINATION", description = "Fetch the backup from this destination.")
    String from;

    @Option(names = "--target-db", description = "Restore into this database. It must already exist for PostgreSQL and MySQL.")
    String targetDb;

    @Option(names = "--target-dir", description = "Folder jobs: extract into this folder, which must be missing or empty.")
    Path targetDir;

    @Option(names = "--yes", description = "Skip the confirmation. Use with care: restoring can overwrite data.")
    boolean yes;

    @Override
    public Integer call() {
        BackupSettings settings = ConfigLoader.load(config);
        JobConfig job = CliSupport.requireJob(settings, jobName);
        if (targetDb != null && !ConfigLoader.SAFE_NAME.matcher(targetDb).matches()) {
            throw new IllegalArgumentException("'" + targetDb + "' isn't a usable database name");
        }
        RestoreService service = new RestoreService(job, settings.getDestinations(),
                ToolInstaller.forThisMachine(true), StoreFactory.standard(), CliSupport.notifier(settings));
        RunReport report = service.restore(new RestoreService.Request(which.file, from, targetDb, targetDir, yes ? null : Prompter::line));
        if (report.succeeded()) {
            System.out.println("Restore successful: " + report.file() + " into " + report.database());
            report.notes().forEach(note -> System.out.println("  " + note));
            return 0;
        }
        System.err.println("Restore failed: " + report.error());
        return 1;
    }
}
