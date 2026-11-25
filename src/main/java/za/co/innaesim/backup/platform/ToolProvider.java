package za.co.innaesim.backup.platform;

import java.nio.file.Path;

/** Supplies the absolute path of a tool, or fails with {@link ToolMissingException}. */
@FunctionalInterface
public interface ToolProvider {
    /**
     * Supplies a tool.
     *
     * @param tool the tool's name
     * @return its absolute path
     * @throws ToolMissingException if it isn't available
     */
    Path require(String tool);
}
