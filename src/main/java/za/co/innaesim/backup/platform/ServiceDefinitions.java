package za.co.innaesim.backup.platform;

import java.util.List;

/** Generates the launchd, systemd and Windows Task Scheduler definitions for the daemon. */
public final class ServiceDefinitions {
    /** The launchd service label. */
    public static final String LABEL = "za.co.innaesim.backup-utility";
    /** The label used before the package rename; services enabled under it are still found and removed. */
    public static final String LEGACY_LABEL = "com.db.backup-utility";

    /**
     * The settings a service definition needs.
     *
     * @param command full daemon command line
     * @param path    PATH for the service to run with
     * @param runAs   user for a system-level service, or null for the default
     * @param system  whether it starts at boot rather than for one user
     */
    public record Spec(List<String> command, String path, String runAs, boolean system) {}

    private ServiceDefinitions() {}

    /**
     * Generates a launchd property list.
     *
     * @param spec the service settings
     * @return the plist's contents
     */
    public static String launchdPlist(Spec spec) {
        StringBuilder args = new StringBuilder();
        for (String arg : spec.command()) args.append("        <string>").append(xml(arg)).append("</string>\n");
        String user = spec.runAs() == null ? "" : "    <key>UserName</key>\n    <string>" + xml(spec.runAs()) + "</string>\n";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key>
                    <string>%s</string>
                    <key>ProgramArguments</key>
                    <array>
                %s    </array>
                    <key>EnvironmentVariables</key>
                    <dict>
                        <key>PATH</key>
                        <string>%s</string>
                    </dict>
                %s    <key>RunAtLoad</key>
                    <true/>
                    <key>KeepAlive</key>
                    <true/>
                    <key>ThrottleInterval</key>
                    <integer>30</integer>
                </dict>
                </plist>
                """.formatted(LABEL, args, xml(spec.path()), user);
    }

    /**
     * Generates a systemd unit file.
     *
     * @param spec the service settings
     * @return the unit's contents
     */
    public static String systemdUnit(Spec spec) {
        StringBuilder exec = new StringBuilder();
        for (String arg : spec.command()) {
            if (exec.length() > 0) exec.append(' ');
            exec.append(systemdQuote(arg));
        }
        String user = spec.runAs() == null ? "" : "User=" + spec.runAs() + "\n";
        return """
                [Unit]
                Description=Database backup utility
                Wants=network-online.target
                After=network-online.target

                [Service]
                Type=simple
                ExecStart=%s
                Environment=%s
                %sRestart=always
                RestartSec=30

                [Install]
                WantedBy=%s
                """.formatted(exec, systemdQuote("PATH=" + spec.path()), user,
                spec.system() ? "multi-user.target" : "default.target");
    }

    /**
     * Generates a Windows task definition. It's XML rather than plain schtasks flags, because only the XML form can restart on failure.
     *
     * @param spec the service settings
     * @return the XML, which must be saved as UTF-16
     */
    public static String windowsTaskXml(Spec spec) {
        String program = spec.command().get(0);
        StringBuilder args = new StringBuilder();
        for (String arg : spec.command().subList(1, spec.command().size())) {
            if (args.length() > 0) args.append(' ');
            args.append('"').append(arg.replace("\"", "\\\"")).append('"');
        }
        String trigger = spec.system() ? "<BootTrigger><Enabled>true</Enabled></BootTrigger>"
                                       : "<LogonTrigger><Enabled>true</Enabled></LogonTrigger>";
        String principal = spec.system()
                ? "<UserId>S-1-5-18</UserId><RunLevel>HighestAvailable</RunLevel>"
                : "<LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel>";
        return """
                <?xml version="1.0" encoding="UTF-16"?>
                <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
                  <Triggers>%s</Triggers>
                  <Principals><Principal id="Author">%s</Principal></Principals>
                  <Settings>
                    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
                    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
                    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
                    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
                    <StartWhenAvailable>true</StartWhenAvailable>
                    <RestartOnFailure><Interval>PT1M</Interval><Count>999</Count></RestartOnFailure>
                  </Settings>
                  <Actions Context="Author">
                    <Exec><Command>%s</Command><Arguments>%s</Arguments></Exec>
                  </Actions>
                </Task>
                """.formatted(trigger, principal, xml(program), xml(args.toString()));
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String systemdQuote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%").replace("$", "$$") + '"';
    }
}
