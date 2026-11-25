package za.co.innaesim.backup.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.InputSource;

import za.co.innaesim.backup.platform.ServiceDefinitions.Spec;
import za.co.innaesim.backup.platform.ServiceInstaller.Result;

class ServiceInstallerTest {
    private static final Path JAR = Path.of("/opt/app/backup-utility.jar");

    @TempDir
    Path home;
    @TempDir
    Path root;

    private final List<String> commands = new ArrayList<>();
    private String uid = "501";

    private ServiceInstaller installer(OperatingSystem os) {
        return new ServiceInstaller(os, command -> {
            commands.add(String.join(" ", command));
            return command.equals(List.of("id", "-u")) ? new Result(0, uid + "\n") : new Result(0, "state = running\n");
        }, home, root);
    }

    private static void assertWellFormed(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // The plist's DOCTYPE points at apple.com; don't fetch it in a test.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.newDocumentBuilder().parse(new InputSource(new java.io.StringReader(xml)));
    }

    private static Spec spec(boolean system, String runAs) {
        return new Spec(List.of("/usr/bin/java", "-jar", "/a b/x&y.jar", "daemon"), "/usr/bin:/bin", runAs, system);
    }

    @Test
    void launchdPlistIsValidAndRestarts() throws Exception {
        String plist = ServiceDefinitions.launchdPlist(spec(true, "dana"));
        assertWellFormed(plist);
        assertTrue(plist.contains("<key>RunAtLoad</key>"));
        assertTrue(plist.contains("<key>KeepAlive</key>"));
        assertTrue(plist.contains("x&amp;y.jar"));
        assertTrue(plist.contains("<string>dana</string>"));
        assertFalse(ServiceDefinitions.launchdPlist(spec(false, null)).contains("UserName"));
    }

    @Test
    void systemdUnitRestartsAndQuotes() {
        String unit = ServiceDefinitions.systemdUnit(spec(true, "dana"));
        assertTrue(unit.contains("Restart=always"), unit);
        assertTrue(unit.contains("User=dana"), unit);
        assertTrue(unit.contains("\"/a b/x&y.jar\""), unit);
        assertTrue(unit.contains("WantedBy=multi-user.target"), unit);
        assertTrue(ServiceDefinitions.systemdUnit(spec(false, null)).contains("WantedBy=default.target"));
    }

    @Test
    void windowsTaskIsValidAndRestarts() throws Exception {
        String system = ServiceDefinitions.windowsTaskXml(spec(true, null));
        assertWellFormed(system);
        assertTrue(system.contains("BootTrigger"));
        assertTrue(system.contains("S-1-5-18"));
        assertTrue(system.contains("RestartOnFailure"));
        assertTrue(ServiceDefinitions.windowsTaskXml(spec(false, null)).contains("LogonTrigger"));
    }

    @Test
    void macUserEnableWritesAgentAndWarnsAboutLogin() throws Exception {
        Path config = home.resolve("cfg/config.yaml");
        String message = installer(OperatingSystem.MACOS).enable(config, false, JAR);
        Path plist = home.resolve("Library/LaunchAgents/za.co.innaesim.backup-utility.plist");
        String content = Files.readString(plist);
        assertTrue(content.contains(JAR.toString()), content);
        assertTrue(content.contains("-Dbackup.utility.home=" + config.getParent()), "state lives beside the config: " + content);
        assertTrue(commands.contains("launchctl bootstrap gui/501 " + plist), commands.toString());
        assertTrue(message.contains("NOT at boot"), message);
    }

    @Test
    void recordsTheStableJavaPathFromTheLauncher() throws Exception {
        System.setProperty("backup.utility.java", "/stable/path/java");
        try {
            installer(OperatingSystem.LINUX).enable(home.resolve("c.yaml"), false, JAR);
        } finally {
            System.clearProperty("backup.utility.java");
        }
        String unit = Files.readString(home.resolve(".config/systemd/user/backup-utility.service"));
        assertTrue(unit.contains("ExecStart=\"/stable/path/java\""), unit);
    }

    @Test
    void systemLevelNeedsRoot() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> installer(OperatingSystem.MACOS).enable(home.resolve("c.yaml"), true, JAR));
        assertTrue(e.getMessage().contains("sudo"));
        assertFalse(Files.exists(root.resolve("Library/LaunchDaemons")), "nothing written");
    }

    @Test
    void macSystemEnableUsesDaemonDirectory() throws Exception {
        uid = "0";
        String message = installer(OperatingSystem.MACOS).enable(home.resolve("c.yaml"), true, JAR);
        assertTrue(Files.exists(root.resolve("Library/LaunchDaemons/za.co.innaesim.backup-utility.plist")));
        assertTrue(commands.stream().anyMatch(c -> c.startsWith("launchctl bootstrap system ")), commands.toString());
        assertTrue(message.contains("at boot"), message);
    }

    @Test
    void linuxUserEnableTurnsOnLinger() throws Exception {
        String message = installer(OperatingSystem.LINUX).enable(home.resolve("c.yaml"), false, JAR);
        assertTrue(Files.exists(home.resolve(".config/systemd/user/backup-utility.service")));
        assertTrue(commands.contains("systemctl --user enable --now backup-utility.service"), commands.toString());
        assertTrue(commands.stream().anyMatch(c -> c.startsWith("loginctl enable-linger")), commands.toString());
        assertTrue(message.contains("without a login"), message);
    }

    @Test
    void linuxSystemEnableWritesSystemUnit() throws Exception {
        uid = "0";
        installer(OperatingSystem.LINUX).enable(home.resolve("c.yaml"), true, JAR);
        assertTrue(Files.exists(root.resolve("etc/systemd/system/backup-utility.service")));
        assertTrue(commands.contains("systemctl enable --now backup-utility.service"), commands.toString());
    }

    @Test
    void windowsEnableCreatesAndRunsTask() throws Exception {
        String message = installer(OperatingSystem.WINDOWS).enable(home.resolve("c.yaml"), false, JAR);
        assertTrue(commands.stream().anyMatch(c -> c.startsWith("schtasks /Create /TN BackupUtility /XML")), commands.toString());
        assertTrue(commands.contains("schtasks /Run /TN BackupUtility"), commands.toString());
        assertTrue(message.contains("NOT at boot"), message);
    }

    @Test
    void disableRemovesDefinition() throws Exception {
        ServiceInstaller installer = installer(OperatingSystem.MACOS);
        installer.enable(home.resolve("c.yaml"), false, JAR);
        Path plist = home.resolve("Library/LaunchAgents/za.co.innaesim.backup-utility.plist");
        assertTrue(Files.exists(plist));
        assertTrue(installer.status(false).contains("state = running"));
        installer.disable(false);
        assertFalse(Files.exists(plist));
        assertTrue(commands.contains("launchctl bootout gui/501/za.co.innaesim.backup-utility"), commands.toString());
        assertTrue(installer.status(false).startsWith("Not enabled"));
    }

    @Test
    void serviceUnderOldLabelIsFoundAndReplaced() throws Exception {
        ServiceInstaller installer = installer(OperatingSystem.MACOS);
        Path legacy = home.resolve("Library/LaunchAgents/com.db.backup-utility.plist");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "<plist/>");
        assertTrue(installer.status(false).startsWith("Enabled under the old name"));

        installer.enable(home.resolve("c.yaml"), false, JAR);
        assertFalse(Files.exists(legacy));
        assertTrue(commands.contains("launchctl bootout gui/501/com.db.backup-utility"), commands.toString());
        assertTrue(Files.exists(home.resolve("Library/LaunchAgents/za.co.innaesim.backup-utility.plist")));

        Files.writeString(legacy, "<plist/>");
        installer.disable(false);
        assertFalse(Files.exists(legacy));
    }

    @Test
    void failedRegistrationIsReported() {
        ServiceInstaller failing = new ServiceInstaller(OperatingSystem.LINUX,
                command -> new Result(1, "no bus"), home, root);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> failing.enable(home.resolve("c.yaml"), false, JAR));
        assertTrue(e.getMessage().contains("no bus"), e.getMessage());
    }

    @Test
    void unknownOsIsRefused() throws IOException, InterruptedException {
        assertThrows(IllegalStateException.class, () -> installer(OperatingSystem.UNKNOWN).enable(home.resolve("c.yaml"), false, JAR));
        assertEquals("Auto-start isn't supported on this operating system", installer(OperatingSystem.UNKNOWN).status(false));
    }
}
