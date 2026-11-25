package za.co.innaesim.backup.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import lombok.extern.slf4j.Slf4j;

/**
 * Finds a tool, offering to install it if it's missing. It never installs without an explicit yes,
 * and never prompts when {@code interactive} is false: a daemon has nobody to answer.
 */
@Slf4j
public class ToolInstaller implements ToolProvider {
    /** Runs an install command attached to the terminal and returns its exit code. */
    @FunctionalInterface
    public interface CommandRunner {
        /**
         * Runs a command.
         *
         * @param command the program and its arguments
         * @return its exit code
         * @throws IOException if it can't be started
         * @throws InterruptedException if interrupted while waiting for it
         */
        int run(List<String> command) throws IOException, InterruptedException;
    }

    private final ToolChecker checker;
    private final PackageManager manager;
    private final boolean root;
    private final boolean interactive;
    private final Predicate<String> confirm;
    private final CommandRunner runner;

    /**
     * Creates an installer.
     *
     * @param checker finds tools
     * @param manager the package manager available
     * @param root whether this is running as root
     * @param interactive whether someone can answer a prompt
     * @param confirm shown a question, returns the answer
     * @param runner runs install commands
     */
    public ToolInstaller(ToolChecker checker, PackageManager manager, boolean root, boolean interactive,
                         Predicate<String> confirm, CommandRunner runner) {
        this.checker = checker;
        this.manager = manager;
        this.root = root;
        this.interactive = interactive;
        this.confirm = confirm;
        this.runner = runner;
    }

    /**
     * Creates an installer for this machine, with prompts on the terminal.
     *
     * @param interactive whether someone can answer a prompt
     * @return the installer
     */
    public static ToolInstaller forThisMachine(boolean interactive) {
        ToolChecker checker = new ToolChecker();
        OperatingSystem os = OperatingSystem.current();
        return new ToolInstaller(checker, PackageManager.detect(os, checker),
                "root".equals(System.getProperty("user.name")), interactive,
                Prompter::confirm, command -> new ProcessBuilder(command).inheritIO().start().waitFor());
    }

    @Override
    public Path require(String tool) {
        Optional<Path> found = checker.find(tool);
        if (found.isPresent()) return found.get();

        InstallPlan plan = InstallPlan.forTool(tool, manager, root);
        if (!plan.canRun()) {
            throw new ToolMissingException(tool + " isn't installed. Install instructions: " + plan.docsUrl());
        }
        String commandLine = String.join(" ", plan.command());
        if (!interactive) {
            throw new ToolMissingException(tool + " isn't installed. To install it, run: " + commandLine);
        }
        System.out.println(tool + " isn't installed. It can be installed with:");
        System.out.println("  " + commandLine);
        if (!confirm.test("Run this command now?")) {
            throw new ToolMissingException(tool + " isn't installed. To install it, run: " + commandLine);
        }
        return install(tool, plan, commandLine);
    }

    private Path install(String tool, InstallPlan plan, String commandLine) {
        int exit;
        try {
            exit = runner.run(plan.command());
        } catch (IOException e) {
            throw new ToolMissingException("Couldn't run '" + commandLine + "': " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolMissingException("Install of " + tool + " was interrupted");
        }
        if (exit != 0) throw new ToolMissingException("'" + commandLine + "' failed with exit code " + exit);
        return checker.find(tool).orElseThrow(() -> new ToolMissingException(
                "'" + commandLine + "' finished, but " + tool + " still can't be found. Add its directory to PATH."));
    }
}
