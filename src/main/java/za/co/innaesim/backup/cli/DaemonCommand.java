package za.co.innaesim.backup.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import za.co.innaesim.backup.config.ConfigLoader;
import za.co.innaesim.backup.schedule.BackupDaemon;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** The {@code daemon} command. */
@Command(name = "daemon", mixinStandardHelpOptions = true,
        description = "Run scheduled backups until stopped. 'service enable' runs this automatically.")
class DaemonCommand implements Callable<Integer> {
    @Option(names = {"-c", "--config"}, description = "Config file. Default: ~/.backup-utility/config.yaml.")
    Path config;

    @Override
    public Integer call() throws IOException {
        // Nobody is at the terminal for a scheduled run, so it must never prompt.
        return new BackupDaemon(() -> ConfigLoader.load(config), (job, settings) -> CliSupport.backup(job, settings, false)).run();
    }
}
