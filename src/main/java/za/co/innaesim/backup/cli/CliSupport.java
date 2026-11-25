package za.co.innaesim.backup.cli;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import za.co.innaesim.backup.config.ConfigLoader;
import za.co.innaesim.backup.database.Drivers;
import za.co.innaesim.backup.folder.Excludes;
import za.co.innaesim.backup.folder.FolderArchiver;
import za.co.innaesim.backup.model.AppConfig;
import za.co.innaesim.backup.model.BackupSettings;
import za.co.innaesim.backup.model.JobConfig;
import za.co.innaesim.backup.notify.Notifier;
import za.co.innaesim.backup.notify.SlackNotifier;
import za.co.innaesim.backup.platform.ToolInstaller;
import za.co.innaesim.backup.service.RunReport;
import za.co.innaesim.backup.service.Service;
import za.co.innaesim.backup.storage.StoreFactory;

/** Wiring shared by the commands. */
final class CliSupport {
    private CliSupport() {}

    static Notifier notifier(BackupSettings settings) {
        return settings.getSlackWebhookUrl() == null
                ? Notifier.NONE
                : new SlackNotifier(settings.getSlackWebhookUrl(), settings.getSlackNotifyOn());
    }

    static RunReport backup(JobConfig job, BackupSettings settings, boolean interactive) {
        return new Service(job, settings.getDestinations(), ToolInstaller.forThisMachine(interactive),
                StoreFactory.standard(), notifier(settings)).runBackup();
    }

    /** Runs one backup, prints the result and returns an exit code. */
    static int runBackup(JobConfig job, BackupSettings settings, boolean interactive) {
        RunReport report = backup(job, settings, interactive);
        if (report.succeeded()) {
            System.out.println("Backup successful: " + Path.of(job.getDatabase().getOutputDir()).toAbsolutePath().resolve(report.file()));
            report.destinations().forEach((name, result) -> System.out.println("  " + name + ": " + result));
            report.notes().forEach(note -> System.out.println("  " + note));
            return 0;
        }
        System.err.println("Backup failed: " + report.error());
        return 1;
    }

    static JobConfig requireJob(BackupSettings settings, String name) {
        JobConfig job = settings.getJobs().get(name);
        if (job == null) {
            throw new IllegalArgumentException("No job named '" + name + "'. Configured jobs: " + settings.getJobs().keySet());
        }
        return job;
    }

    /** Job names and file patterns from the command line get the same checks as those from a config file. */
    static void validateAdHoc(JobConfig job) {
        AppConfig db = job.getDatabase();
        if (FolderArchiver.isFolder(db.getType())) {
            if (db.getPaths().isEmpty()) throw new IllegalArgumentException("A folder job needs at least one folder");
            Set<String> tops = new HashSet<>();
            for (String path : db.getPaths()) {
                if (!tops.add(FolderArchiver.topName(Path.of(path)))) {
                    throw new IllegalArgumentException("Two folders are both named '" + FolderArchiver.topName(Path.of(path))
                            + "', so they'd clash in the archive. Back them up separately");
                }
            }
            Excludes.compile(db.getExcludes());
        } else {
            Drivers.forType(db.getType());
        }
        if (!ConfigLoader.SAFE_NAME.matcher(db.getName()).matches()) {
            throw new IllegalArgumentException("'" + db.getName() + "' isn't a usable database name");
        }
        String pattern = db.getFilepattern();
        if (!pattern.contains("{date}")) throw new IllegalArgumentException("--file-pattern must contain {date}");
        if (pattern.contains("/") || pattern.contains("\\")) throw new IllegalArgumentException("--file-pattern can't contain a path separator");
    }
}
