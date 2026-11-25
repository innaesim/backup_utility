package za.co.innaesim.backup.database;

import java.util.List;
import java.util.Map;

/**
 * A command line plus any extra environment variables it needs.
 *
 * @param command the program and its arguments
 * @param environment variables to add to the inherited environment
 */
public record ProcessSpec(List<String> command, Map<String, String> environment) {}
