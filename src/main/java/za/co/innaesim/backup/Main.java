package za.co.innaesim.backup;

import za.co.innaesim.backup.cli.RootCommand;
import za.co.innaesim.backup.config.AppPaths;

import picocli.CommandLine;

/** Program entry point. */
public class Main {
    private Main() {}

    /**
     * Runs the command line and exits with its exit code.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        // Must happen before anything starts log4j, whose log file path depends on it.
        System.setProperty(AppPaths.HOME_PROPERTY, AppPaths.home().toString());
        System.exit(commandLine().execute(args));
    }

    /**
     * Builds the command line, with errors reported as plain messages rather than stack traces.
     *
     * @return a configured command line, ready to execute
     */
    public static CommandLine commandLine() {
        CommandLine commandLine = new CommandLine(new RootCommand());
        commandLine.setExecutionExceptionHandler((e, cmd, parsed) -> {
            cmd.getErr().println("Error: " + (e.getMessage() != null ? e.getMessage() : e));
            return 1;
        });
        return commandLine;
    }
}
