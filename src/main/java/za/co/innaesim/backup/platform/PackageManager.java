package za.co.innaesim.backup.platform;

import java.util.List;

/** Package managers the utility can suggest install commands for. */
public enum PackageManager {
    /** Homebrew, on macOS. */
    BREW("brew"),
    /** APT, on Debian and Ubuntu. */
    APT("apt-get"),
    /** DNF, on Fedora and current RHEL. */
    DNF("dnf"),
    /** YUM, on older RHEL-style systems. */
    YUM("yum"),
    /** winget, on Windows. */
    WINGET("winget"),
    /** No supported package manager was found. */
    NONE("none");

    private final String executable;

    PackageManager(String executable) {
        this.executable = executable;
    }

    /**
     * Names the program behind this package manager.
     *
     * @return the executable name
     */
    public String executable() {
        return executable;
    }

    /**
     * Finds the package manager on this machine.
     *
     * @param os the current OS
     * @param tools used to look for each candidate
     * @return the first one found, or {@link #NONE}
     */
    public static PackageManager detect(OperatingSystem os, ToolChecker tools) {
        List<PackageManager> candidates = switch (os) {
            case MACOS -> List.of(BREW);
            case LINUX -> List.of(APT, DNF, YUM);
            case WINDOWS -> List.of(WINGET);
            case UNKNOWN -> List.of();
        };
        for (PackageManager candidate : candidates) {
            if (tools.find(candidate.executable).isPresent()) return candidate;
        }
        return NONE;
    }
}
