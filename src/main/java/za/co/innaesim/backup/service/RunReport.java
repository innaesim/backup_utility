package za.co.innaesim.backup.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The outcome of a backup or restore.
 *
 * @param operation    what ran: {@code Backup} or {@code Restore}
 * @param job          the job's name
 * @param database     a description of the database
 * @param succeeded    whether it worked
 * @param duration     how long it took
 * @param file         the backup file's name, or null if it never got that far
 * @param sizeBytes    the backup's size, or null if not known
 * @param destinations destination name to result text, in configured order
 * @param notes        other things worth reporting, such as what retention removed
 * @param error        why the run failed, or null if it succeeded
 */
public record RunReport(String operation, String job, String database, boolean succeeded, Duration duration,
                        String file, Long sizeBytes, Map<String, String> destinations, List<String> notes, String error) {}
