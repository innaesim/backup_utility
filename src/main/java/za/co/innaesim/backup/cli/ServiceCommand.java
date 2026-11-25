package za.co.innaesim.backup.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import za.co.innaesim.backup.config.AppPaths;
import za.co.innaesim.backup.config.ConfigLoader;
import za.co.innaesim.backup.model.BackupSettings;
import za.co.innaesim.backup.platform.ServiceInstaller;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** The {@code service} command group, which manages auto-start. */
@Command(name = "service", mixinStandardHelpOptions = true,
        description = "Make the daemon start automatically and come back after a restart.",
        subcommands = {ServiceCommand.Enable.class, ServiceCommand.Disable.class, ServiceCommand.Status.class})
class ServiceCommand {

    /** The {@code service enable} command. */
    @Command(name = "enable", mixinStandardHelpOptions = true, description = "Register the daemon with the OS and start it.")
    static class Enable implements Callable<Integer> {
        @Option(names = {"-c", "--config"}, description = "Config file. Default: ~/.backup-utility/config.yaml.")
        Path config;

        @Option(names = "--system", description = "Start at boot, with nobody logged in. Needs root or Administrator.")
        boolean system;

        @Override
        public Integer call() throws IOException, InterruptedException {
            Path file = config != null ? config : AppPaths.defaultConfig();
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Config file not found: " + file + ". The service needs one, even if it's empty.");
            }
            BackupSettings settings = ConfigLoader.load(file);
            if (settings.getJobs().values().stream().noneMatch(job -> job.getSchedule() != null)) {
                System.out.println("Note: no jobs are scheduled yet, so the daemon will have nothing to do until you add one.");
            }
            System.out.println(new ServiceInstaller().enable(file, system));
            System.out.println("Services don't inherit your shell's environment. Put any ${VAR} values in "
                    + file.toAbsolutePath().resolveSibling("env") + " (chmod 600).");
            return 0;
        }
    }

    /** The {@code service disable} command. */
    @Command(name = "disable", mixinStandardHelpOptions = true, description = "Stop the daemon and unregister it.")
    static class Disable implements Callable<Integer> {
        @Option(names = "--system", description = "Disable the system-level service.")
        boolean system;

        @Override
        public Integer call() throws IOException, InterruptedException {
            new ServiceInstaller().disable(system);
            System.out.println("Disabled.");
            return 0;
        }
    }

    /** The {@code service status} command. */
    @Command(name = "status", mixinStandardHelpOptions = true, description = "Show whether the service is enabled and running.")
    static class Status implements Callable<Integer> {
        @Option(names = "--system", description = "Check the system-level service.")
        boolean system;

        @Override
        public Integer call() throws IOException, InterruptedException {
            System.out.println(new ServiceInstaller().status(system));
            return 0;
        }
    }
}
