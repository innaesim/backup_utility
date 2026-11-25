package za.co.innaesim.backup.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import za.co.innaesim.backup.config.AppPaths;
import za.co.innaesim.backup.database.DatabaseDriver;
import za.co.innaesim.backup.database.Drivers;
import za.co.innaesim.backup.folder.FolderArchiver;
import za.co.innaesim.backup.model.AppConfig;
import za.co.innaesim.backup.model.JobConfig;
import za.co.innaesim.backup.platform.Prompter;

import picocli.CommandLine.Command;

/** With no subcommand, keeps the original behaviour: prompt for settings and run one backup. */
@Command(name = "backup-utility",
        mixinStandardHelpOptions = true,
        version = "backup-utility 0.0.1-SNAPSHOT",
        description = "Back up and restore PostgreSQL, MySQL and MongoDB databases, and folders.",
        subcommands = {BackupCommand.class, RestoreCommand.class, ListCommand.class, ScheduleCommand.class,
                DaemonCommand.class, ServiceCommand.class, DoctorCommand.class})
public class RootCommand implements Callable<Integer> {
    /** Creates the root command. */
    public RootCommand() {}

    @Override
    public Integer call() {
        String type = required(Prompter.line("Enter type (postgresql/mysql/mongodb/folder):")).toLowerCase();
        if (FolderArchiver.isFolder(type)) return folder();
        DatabaseDriver driver = Drivers.forType(type);

        AppConfig db = new AppConfig();
        db.setType(type);
        db.setHost(orDefault(Prompter.line("Enter host [localhost]:"), "localhost"));
        String port = orDefault(Prompter.line("Enter port [" + driver.defaultPort() + "]:"), String.valueOf(driver.defaultPort()));
        try {
            db.setPort(Integer.valueOf(port));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + port + "' isn't a valid port");
        }
        db.setName(required(Prompter.line("Enter database name:")));
        db.setUser(emptyToNull(Prompter.line("Enter username:")));
        db.setPassword(emptyToNull(Prompter.password("Enter password:")));
        db.setOutputDir(orDefault(Prompter.line("Enter output directory [./backups]:"), "./backups"));
        db.setCompress(Prompter.confirm("Compress backup?"));

        JobConfig job = new JobConfig();
        job.setName(db.getName());
        job.setDatabase(db);
        CliSupport.validateAdHoc(job);
        return CliSupport.runBackup(job, new za.co.innaesim.backup.model.BackupSettings(), true);
    }

    private static Integer folder() {
        String path = required(Prompter.line("Enter folder to back up:"));
        AppConfig config = new AppConfig();
        config.setType(FolderArchiver.TYPE);
        config.setPaths(List.of(Path.of(AppPaths.expandTilde(path)).toAbsolutePath().normalize().toString()));
        config.setName(ConnectionOptions.folderJobName(config.getPaths().get(0)));
        config.setOutputDir(orDefault(Prompter.line("Enter output directory [./backups]:"), "./backups"));
        config.setCompress(Prompter.confirm("Compress backup?"));

        JobConfig job = new JobConfig();
        job.setName(config.getName());
        job.setDatabase(config);
        CliSupport.validateAdHoc(job);
        return CliSupport.runBackup(job, new za.co.innaesim.backup.model.BackupSettings(), true);
    }

    private static String required(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("A value is required");
        return value;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
