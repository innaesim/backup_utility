package za.co.innaesim.backup.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import za.co.innaesim.backup.config.AppPaths;
import za.co.innaesim.backup.platform.ServiceDefinitions.Spec;

/** Registers the daemon with the OS so it comes back after a restart. */
public class ServiceInstaller {
    private static final String WINDOWS_TASK = "BackupUtility";

    /**
     * The outcome of a command.
     *
     * @param exitCode its exit code
     * @param output what it printed
     */
    public record Result(int exitCode, String output) {
        boolean ok() { return exitCode == 0; }
    }

    /** Runs system commands, so tests can replace them. */
    @FunctionalInterface
    public interface Exec {
        /**
         * Runs a command and waits for it.
         *
         * @param command the program and its arguments
         * @return the outcome
         * @throws IOException if it can't be run
         * @throws InterruptedException if interrupted while waiting
         */
        Result run(List<String> command) throws IOException, InterruptedException;
    }

    private final OperatingSystem os;
    private final Exec exec;
    private final Path userHome;
    private final Path systemRoot;

    /** Creates an installer for this machine. */
    public ServiceInstaller() {
        this(OperatingSystem.current(), ServiceInstaller::runCommand, Path.of(System.getProperty("user.home")), Path.of("/"));
    }

    /**
     * Creates an installer with specific locations.
     *
     * @param os the OS to install for
     * @param exec runs system commands
     * @param userHome the user's home directory
     * @param systemRoot where system-level definitions go; only tests change it
     */
    public ServiceInstaller(OperatingSystem os, Exec exec, Path userHome, Path systemRoot) {
        this.os = os;
        this.exec = exec;
        this.userHome = userHome;
        this.systemRoot = systemRoot;
    }

    /**
     * Registers the daemon and starts it, using the running jar.
     *
     * @param config the config file for the daemon to use
     * @param system whether to start at boot rather than for this user
     * @return what the user should know about how this service behaves
     * @throws IOException if the definition can't be written or a command can't be run
     * @throws InterruptedException if interrupted while waiting for a command
     */
    public String enable(Path config, boolean system) throws IOException, InterruptedException {
        return enable(config, system, runningJar());
    }

    String enable(Path config, boolean system, Path jar) throws IOException, InterruptedException {
        requireRootIfNeeded(system);
        // schedules.d, logs and the env file live beside the config, even under sudo where the home differs.
        List<String> command = daemonCommand(jar, config.toAbsolutePath());
        String runAs = system ? blankToNull(System.getenv("SUDO_USER")) : null;
        Spec spec = new Spec(command, servicePath(), runAs, system);
        return switch (os) {
            case MACOS -> enableMac(spec, system);
            case LINUX -> enableLinux(spec, system);
            case WINDOWS -> enableWindows(spec, system);
            case UNKNOWN -> throw new IllegalStateException("Auto-start isn't supported on this operating system");
        };
    }

    /**
     * Stops the daemon and removes its definition.
     *
     * @param system whether to disable the system-level service
     * @throws IOException if the definition can't be removed or a command can't be run
     * @throws InterruptedException if interrupted while waiting for a command
     */
    public void disable(boolean system) throws IOException, InterruptedException {
        requireRootIfNeeded(system);
        switch (os) {
            case MACOS -> {
                removeMac(system, ServiceDefinitions.LABEL);
                removeMac(system, ServiceDefinitions.LEGACY_LABEL);
            }
            case LINUX -> {
                exec.run(systemctl(system, "disable", "--now", "backup-utility.service"));
                Files.deleteIfExists(linuxFile(system));
                exec.run(systemctl(system, "daemon-reload"));
            }
            case WINDOWS -> {
                Result result = exec.run(List.of("schtasks", "/Delete", "/TN", WINDOWS_TASK, "/F"));
                if (!result.ok()) throw new IllegalStateException("schtasks failed: " + result.output());
            }
            case UNKNOWN -> throw new IllegalStateException("Auto-start isn't supported on this operating system");
        }
    }

    /**
     * Describes whether the service is enabled and running.
     *
     * @param system whether to check the system-level service
     * @return a description for showing to the user
     * @throws IOException if a command can't be run
     * @throws InterruptedException if interrupted while waiting for a command
     */
    public String status(boolean system) throws IOException, InterruptedException {
        return switch (os) {
            case MACOS -> {
                Path file = macFile(system, ServiceDefinitions.LABEL);
                Path legacy = macFile(system, ServiceDefinitions.LEGACY_LABEL);
                if (!Files.exists(file) && Files.exists(legacy)) {
                    yield "Enabled under the old name (" + legacy + "). Run 'service disable' and then 'service enable' to update it.";
                }
                if (!Files.exists(file)) yield "Not enabled (" + file + " doesn't exist)";
                Result result = exec.run(List.of("launchctl", "print", macDomain(system) + "/" + ServiceDefinitions.LABEL));
                String state = result.output().lines().map(String::strip).filter(l -> l.startsWith("state =")).findFirst().orElse("state = not loaded");
                yield "Enabled (" + file + "), " + state;
            }
            case LINUX -> {
                Path file = linuxFile(system);
                if (!Files.exists(file)) yield "Not enabled (" + file + " doesn't exist)";
                Result active = exec.run(systemctl(system, "is-active", "backup-utility.service"));
                yield "Enabled (" + file + "), state = " + active.output().strip();
            }
            case WINDOWS -> {
                Result result = exec.run(List.of("schtasks", "/Query", "/TN", WINDOWS_TASK));
                yield result.ok() ? result.output().strip() : "Not enabled";
            }
            case UNKNOWN -> "Auto-start isn't supported on this operating system";
        };
    }

    private String enableMac(Spec spec, boolean system) throws IOException, InterruptedException {
        // A service still enabled under the old label would run a second daemon alongside the new one.
        removeMac(system, ServiceDefinitions.LEGACY_LABEL);
        Path file = macFile(system, ServiceDefinitions.LABEL);
        Files.createDirectories(file.getParent());
        Files.writeString(file, ServiceDefinitions.launchdPlist(spec));
        String domain = macDomain(system);
        exec.run(List.of("launchctl", "bootout", domain + "/" + ServiceDefinitions.LABEL)); // fails harmlessly if not loaded
        check(exec.run(List.of("launchctl", "bootstrap", domain, file.toString())), "launchctl bootstrap");
        return system
                ? "Enabled at " + file + ". The daemon starts at boot, with nobody logged in."
                : "Enabled at " + file + ". The daemon starts when you log in, NOT at boot: after a restart, "
                  + "no backups run until you log in. For boot-time start, run: sudo ... service enable --system";
    }

    private String enableLinux(Spec spec, boolean system) throws IOException, InterruptedException {
        Path file = linuxFile(system);
        Files.createDirectories(file.getParent());
        Files.writeString(file, ServiceDefinitions.systemdUnit(spec));
        check(exec.run(systemctl(system, "daemon-reload")), "systemctl daemon-reload");
        check(exec.run(systemctl(system, "enable", "--now", "backup-utility.service")), "systemctl enable");
        if (system) return "Enabled at " + file + ". The daemon starts at boot.";
        Result linger = exec.run(List.of("loginctl", "enable-linger", System.getProperty("user.name")));
        return linger.ok()
                ? "Enabled at " + file + ". Lingering is on, so the daemon starts at boot without a login."
                : "Enabled at " + file + ", but 'loginctl enable-linger' failed, so the daemon only starts when you log in. "
                  + "Ask an admin to run it, or use: sudo ... service enable --system";
    }

    private String enableWindows(Spec spec, boolean system) throws IOException, InterruptedException {
        Path xml = Files.createTempFile("backup-utility-task", ".xml");
        try {
            // schtasks expects UTF-16, which the XML declaration also states; Java adds the BOM.
            Files.write(xml, ServiceDefinitions.windowsTaskXml(spec).getBytes(StandardCharsets.UTF_16));
            check(exec.run(List.of("schtasks", "/Create", "/TN", WINDOWS_TASK, "/XML", xml.toString(), "/F")), "schtasks /Create");
        } finally {
            Files.deleteIfExists(xml);
        }
        check(exec.run(List.of("schtasks", "/Run", "/TN", WINDOWS_TASK)), "schtasks /Run");
        return system ? "Enabled as task " + WINDOWS_TASK + ". The daemon starts at boot."
                      : "Enabled as task " + WINDOWS_TASK + ". The daemon starts when you log in, NOT at boot. "
                        + "For boot-time start, run from an Administrator prompt: ... service enable --system";
    }

    private void removeMac(boolean system, String label) throws IOException, InterruptedException {
        Path file = macFile(system, label);
        if (label.equals(ServiceDefinitions.LEGACY_LABEL) && !Files.exists(file)) return;
        exec.run(List.of("launchctl", "bootout", macDomain(system) + "/" + label)); // fails harmlessly if not loaded
        Files.deleteIfExists(file);
    }

    private Path macFile(boolean system, String label) {
        String name = label + ".plist";
        return system ? systemRoot.resolve("Library/LaunchDaemons").resolve(name)
                      : userHome.resolve("Library/LaunchAgents").resolve(name);
    }

    private Path linuxFile(boolean system) {
        return system ? systemRoot.resolve("etc/systemd/system/backup-utility.service")
                      : userHome.resolve(".config/systemd/user/backup-utility.service");
    }

    private String macDomain(boolean system) throws IOException, InterruptedException {
        if (system) return "system";
        Result uid = exec.run(List.of("id", "-u"));
        check(uid, "id -u");
        return "gui/" + uid.output().strip();
    }

    private static List<String> systemctl(boolean system, String... args) {
        List<String> command = new ArrayList<>(List.of("systemctl"));
        if (!system) command.add("--user");
        command.addAll(List.of(args));
        return command;
    }

    private void requireRootIfNeeded(boolean system) throws IOException, InterruptedException {
        if (!system || os == OperatingSystem.WINDOWS) return; // on Windows, schtasks reports missing rights itself
        Result uid = exec.run(List.of("id", "-u"));
        if (!"0".equals(uid.output().strip())) {
            throw new IllegalStateException("--system needs root. Re-run with sudo.");
        }
    }

    private static void check(Result result, String what) {
        if (!result.ok()) throw new IllegalStateException(what + " failed: " + result.output().strip());
    }

    private static List<String> daemonCommand(Path jar, Path config) {
        // The launcher names a stable path. Otherwise the OS reports the resolved one, such as a versioned
        // Homebrew directory, and the service stops starting after a Java upgrade.
        String java = System.getProperty("backup.utility.java");
        if (java == null || java.isBlank()) {
            java = ProcessHandle.current().info().command().orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        }
        return List.of(java, "-D" + AppPaths.HOME_PROPERTY + "=" + config.getParent(), "-jar", jar.toString(), "daemon", "--config", config.toString());
    }

    private static Path runningJar() {
        try {
            Path location = Path.of(ServiceInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!location.toString().endsWith(".jar")) {
                throw new IllegalStateException("Auto-start must be set up from the packaged jar (mvn package, then java -jar target/backup-utility.jar ...)");
            }
            return location;
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("Can't work out where the jar is: " + e.getMessage());
        }
    }

    private static String servicePath() {
        String path = System.getenv("PATH");
        return path == null || path.isBlank() ? "/usr/local/bin:/usr/bin:/bin" : path;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Result runCommand(List<String> command) throws IOException, InterruptedException {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(process.waitFor(), output);
        } catch (IOException e) {
            return new Result(127, command.get(0) + ": " + e.getMessage());
        }
    }
}
