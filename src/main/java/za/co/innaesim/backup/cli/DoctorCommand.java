package za.co.innaesim.backup.cli;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import za.co.innaesim.backup.database.DatabaseDriver;
import za.co.innaesim.backup.database.Drivers;
import za.co.innaesim.backup.platform.InstallPlan;
import za.co.innaesim.backup.platform.OperatingSystem;
import za.co.innaesim.backup.platform.PackageManager;
import za.co.innaesim.backup.platform.ToolChecker;
import za.co.innaesim.backup.platform.ToolInstaller;
import za.co.innaesim.backup.platform.ToolMissingException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** The {@code doctor} command. */
@Command(name = "doctor", mixinStandardHelpOptions = true,
        description = "Show the operating system and whether the database tools are installed.")
class DoctorCommand implements Callable<Integer> {
    @Option(names = "--install", description = "Offer to install each missing tool.")
    boolean install;

    @Override
    public Integer call() {
        OperatingSystem os = OperatingSystem.current();
        ToolChecker checker = new ToolChecker();
        PackageManager manager = PackageManager.detect(os, checker);
        System.out.println("Operating system: " + os.label() + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("Package manager:  " + (manager == PackageManager.NONE ? "none found" : manager.executable()));
        System.out.println("Java:             " + System.getProperty("java.version"));
        System.out.println();

        Set<String> missing = new LinkedHashSet<>();
        for (DatabaseDriver driver : Drivers.ALL) {
            System.out.println(driver.type() + ":");
            for (String tool : new String[] {driver.backupTool(), driver.restoreTool()}) {
                Optional<Path> found = checker.find(tool);
                if (found.isPresent()) {
                    System.out.println("  " + tool + ": " + found.get() + " (" + ToolChecker.version(found.get()) + ")");
                } else {
                    missing.add(tool);
                    System.out.println("  " + tool + ": MISSING - " + installHint(tool, manager));
                }
            }
        }
        if (!install) return 0;

        ToolInstaller installer = ToolInstaller.forThisMachine(true);
        boolean failed = false;
        for (String tool : missing) {
            if (checker.find(tool).isPresent()) continue; // installed along with an earlier tool
            System.out.println();
            try {
                installer.require(tool);
                System.out.println(tool + " is now installed.");
            } catch (ToolMissingException e) {
                System.err.println(e.getMessage());
                failed = true;
            }
        }
        return failed ? 1 : 0;
    }

    private static String installHint(String tool, PackageManager manager) {
        InstallPlan plan = InstallPlan.forTool(tool, manager, "root".equals(System.getProperty("user.name")));
        return plan.canRun() ? String.join(" ", plan.command()) : plan.docsUrl();
    }
}
