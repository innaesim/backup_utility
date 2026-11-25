package za.co.innaesim.backup.retention;

import java.time.LocalDateTime;

/**
 * A recognised backup file.
 *
 * @param name the file name
 * @param createdAt when it was taken, according to its name
 */
public record BackupEntry(String name, LocalDateTime createdAt) {}
