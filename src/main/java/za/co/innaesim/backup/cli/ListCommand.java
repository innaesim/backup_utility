package za.co.innaesim.backup.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import za.co.innaesim.backup.config.ConfigLoader;
import za.co.innaesim.backup.model.BackupSettings;
import za.co.innaesim.backup.model.JobConfig;
import za.co.innaesim.backup.notify.Notifier;
import za.co.innaesim.backup.retention.BackupEntry;
import za.co.innaesim.backup.service.RestoreService;
import za.co.innaesim.backup.storage.StoreFactory;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** The {@code list} command. */
@Command(name = "list", mixinStandardHelpOptions = true, description = "List a job's backups, newest first.")
class ListCommand implements Callable<Integer> {
    @Option(names = {"-c", "--config"}, description = "Config file. Default: ~/.backup-utility/config.yaml.")
    Path config;

    @Option(names = {"-j", "--job"}, required = true, description = "Job to list.")
    String jobName;

    @Option(names = "--from", paramLabel = "DESTINATION", description = "List this destination instead of the local directory.")
    String from;

    @Override
    public Integer call() throws IOException {
        BackupSettings settings = ConfigLoader.load(config);
        JobConfig job = CliSupport.requireJob(settings, jobName);
        RestoreService service = new RestoreService(job, settings.getDestinations(), tool -> null, StoreFactory.standard(), Notifier.NONE);
        List<BackupEntry> entries = service.list(from);
        if (entries.isEmpty()) System.out.println("No backups found.");
        entries.forEach(entry -> System.out.println(entry.createdAt().toString().replace('T', ' ') + "  " + entry.name()));
        return 0;
    }
}
