package za.co.innaesim.backup.platform;

import java.util.Locale;

/** The OS this program is running on. */
public enum OperatingSystem {
    /** Apple macOS. */
    MACOS("macOS"),
    /** Any Linux distribution. */
    LINUX("Linux"),
    /** Microsoft Windows. */
    WINDOWS("Windows"),
    /** Anything else; install and auto-start features aren't available. */
    UNKNOWN("Unknown");

    private final String label;

    OperatingSystem(String label) {
        this.label = label;
    }

    /**
     * Gives a name for showing to people.
     *
     * @return the display name
     */
    public String label() {
        return label;
    }

    /**
     * Detects the OS this program is running on.
     *
     * @return the current OS
     */
    public static OperatingSystem current() {
        return fromName(System.getProperty("os.name", ""));
    }

    /**
     * Interprets an OS name, in the form of the {@code os.name} system property.
     *
     * @param osName the name to interpret
     * @return the matching OS, or {@link #UNKNOWN}
     */
    public static OperatingSystem fromName(String osName) {
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.contains("mac") || name.contains("darwin")) return MACOS;
        if (name.contains("win")) return WINDOWS;
        if (name.contains("linux")) return LINUX;
        return UNKNOWN;
    }
}
