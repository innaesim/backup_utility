package za.co.innaesim.backup.platform;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Finds tools on PATH and in the usual install locations. The extra locations matter because
 * Homebrew doesn't link libpq or mysql-client onto PATH, and services run with a minimal PATH.
 */
public class ToolChecker {
    private final OperatingSystem os;
    private final String path;
    private final List<String> extraDirs;

    /** Creates a checker for this machine, using its {@code PATH} and usual install locations. */
    public ToolChecker() {
        this(OperatingSystem.current(), System.getenv("PATH"), knownDirs(OperatingSystem.current()));
    }

    /**
     * Creates a checker with specific search locations.
     *
     * @param os the OS, which decides whether to look for {@code .exe} files
     * @param path a {@code PATH}-style list of directories; may be null
     * @param extraDirs further directories, where one path segment may contain {@code *}
     */
    public ToolChecker(OperatingSystem os, String path, List<String> extraDirs) {
        this.os = os;
        this.path = path == null ? "" : path;
        this.extraDirs = extraDirs;
    }

    /**
     * Looks for an executable tool.
     *
     * @param tool the tool's name, without any extension
     * @return its absolute path, or empty if it wasn't found
     */
    public Optional<Path> find(String tool) {
        String fileName = os == OperatingSystem.WINDOWS ? tool + ".exe" : tool;
        for (Path dir : searchDirs()) {
            Path candidate = dir.resolve(fileName);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /**
     * Asks a tool for its version.
     *
     * @param tool path of the tool
     * @return the first line of {@code tool --version}, or a placeholder if the tool won't say
     */
    public static String version(Path tool) {
        try {
            Process process = new ProcessBuilder(tool.toString(), "--version")
                    .redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(nullDevice())).start();
            byte[] output = process.getInputStream().readNBytes(2048);
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            String text = new String(output, StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? "version unknown" : text.lines().findFirst().orElse("version unknown");
        } catch (IOException e) {
            return "version unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "version unknown";
        }
    }

    private static File nullDevice() {
        return new File(OperatingSystem.current() == OperatingSystem.WINDOWS ? "NUL" : "/dev/null");
    }

    private List<Path> searchDirs() {
        List<Path> dirs = new ArrayList<>();
        for (String entry : path.split(File.pathSeparator)) {
            if (!entry.isBlank()) dirs.add(Path.of(entry));
        }
        for (String pattern : extraDirs) dirs.addAll(expand(pattern));
        return dirs;
    }

    /** Expands one '*' path segment, newest version first, as in /usr/lib/postgresql/&#42;/bin. */
    private static List<Path> expand(String pattern) {
        int star = pattern.indexOf('*');
        if (star < 0) return List.of(Path.of(pattern));
        int slash = Math.max(pattern.lastIndexOf('/', star), pattern.lastIndexOf('\\', star));
        Path parent = Path.of(pattern.substring(0, slash));
        int end = nextSeparator(pattern, star);
        String glob = pattern.substring(slash + 1, end);
        String rest = end < pattern.length() ? pattern.substring(end + 1) : "";
        List<Path> matches = new ArrayList<>();
        if (!Files.isDirectory(parent)) return matches;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, glob)) {
            stream.forEach(match -> matches.add(rest.isEmpty() ? match : match.resolve(rest)));
        } catch (IOException e) {
            return List.of();
        }
        matches.sort(Comparator.reverseOrder());
        return matches;
    }

    private static int nextSeparator(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) == '/' || text.charAt(i) == '\\') return i;
        }
        return text.length();
    }

    static List<String> knownDirs(OperatingSystem os) {
        return switch (os) {
            case MACOS -> List.of(
                    "/opt/homebrew/bin", "/usr/local/bin",
                    "/opt/homebrew/opt/libpq/bin", "/usr/local/opt/libpq/bin",
                    "/opt/homebrew/opt/mysql-client/bin", "/usr/local/opt/mysql-client/bin",
                    "/opt/homebrew/opt/postgresql*/bin", "/usr/local/opt/postgresql*/bin",
                    "/Applications/Postgres.app/Contents/Versions/latest/bin");
            case LINUX -> List.of("/usr/local/bin", "/usr/bin", "/usr/lib/postgresql/*/bin", "/usr/pgsql-*/bin");
            case WINDOWS -> List.of(
                    "C:\\Program Files\\PostgreSQL\\*\\bin",
                    "C:\\Program Files\\MySQL\\*\\bin",
                    "C:\\Program Files\\MongoDB\\Tools\\*\\bin");
            case UNKNOWN -> List.of();
        };
    }
}
