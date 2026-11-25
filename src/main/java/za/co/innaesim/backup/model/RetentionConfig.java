package za.co.innaesim.backup.model;

/**
 * How long to keep backups. A backup is kept if it is within {@code days} or among the newest {@code keepLast}.
 *
 * @param days keep backups newer than this many days; null if not set
 * @param keepLast keep this many of the newest backups; null if not set
 */
public record RetentionConfig(Integer days, Integer keepLast) {}
