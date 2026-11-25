package za.co.innaesim.backup.folder;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * Glob patterns for files and folders to leave out of a folder backup.
 *
 * A pattern without a {@code /}, such as {@code node_modules} or {@code *.tmp}, matches a file or
 * folder with that name at any depth. A pattern with a {@code /}, such as {@code cache/**}, matches
 * the path relative to the folder being backed up. An excluded folder isn't entered at all.
 */
public final class Excludes {
    /** Excludes nothing. */
    public static final Excludes NONE = new Excludes(List.of(), List.of());

    private final List<PathMatcher> byName;
    private final List<PathMatcher> byPath;

    private Excludes(List<PathMatcher> byName, List<PathMatcher> byPath) {
        this.byName = byName;
        this.byPath = byPath;
    }

    /**
     * Compiles exclude patterns.
     *
     * @param patterns glob patterns; may be null or empty
     * @return the compiled patterns
     * @throws IllegalArgumentException naming the first pattern that isn't usable
     */
    public static Excludes compile(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return NONE;
        List<PathMatcher> byName = new ArrayList<>();
        List<PathMatcher> byPath = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) throw new IllegalArgumentException("An exclude pattern is empty");
            if (pattern.startsWith("/") || pattern.startsWith("\\")) {
                throw new IllegalArgumentException("Exclude pattern '" + pattern + "' starts with a slash; patterns are relative to each folder, so drop it");
            }
            String glob = pattern.endsWith("/") ? pattern.substring(0, pattern.length() - 1) : pattern;
            PathMatcher matcher;
            try {
                matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Exclude pattern '" + pattern + "' isn't a valid glob: " + e.getMessage());
            }
            (glob.contains("/") ? byPath : byName).add(matcher);
        }
        return new Excludes(List.copyOf(byName), List.copyOf(byPath));
    }

    /**
     * Checks a path against the patterns.
     *
     * @param relative the path relative to the folder being backed up; not empty
     * @return whether to leave it out
     */
    public boolean excludes(Path relative) {
        Path name = relative.getFileName();
        for (PathMatcher matcher : byName) {
            if (name != null && matcher.matches(name)) return true;
        }
        for (PathMatcher matcher : byPath) {
            if (matcher.matches(relative)) return true;
        }
        return false;
    }
}
