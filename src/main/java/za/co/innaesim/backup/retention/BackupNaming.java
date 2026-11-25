package za.co.innaesim.backup.retention;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import za.co.innaesim.backup.database.Drivers;
import za.co.innaesim.backup.folder.FolderArchiver;
import za.co.innaesim.backup.model.AppConfig;

/**
 * Builds backup file names and recognises them again. Retention and restore only ever act on names
 * that match, so unrelated files and unfinished *.partial files are never touched.
 */
public final class BackupNaming {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final String template;
    private final Pattern matcher;

    /**
     * Creates naming rules for one job.
     *
     * @param db supplies the type, database name and file pattern
     */
    public BackupNaming(AppConfig db) {
        String extension = fileExtension(db.getType());
        this.template = db.getFilepattern().replace("{db}", db.getName()).replace("{ext}", extension);
        this.matcher = Pattern.compile(toRegex(template));
    }

    /**
     * Gives the extension of a job type's backup files, before any {@code .gz}.
     *
     * @param type a job type, such as {@code mysql} or {@code folder}
     * @return the extension, including its dot
     * @throws IllegalArgumentException if the type isn't supported
     */
    public static String fileExtension(String type) {
        return FolderArchiver.isFolder(type) ? FolderArchiver.EXTENSION : Drivers.forType(type).fileExtension();
    }

    private static String toRegex(String template) {
        int date = template.indexOf("{date}");
        return Pattern.quote(template.substring(0, date)) + "(\\d{8}_\\d{6})"
                + Pattern.quote(template.substring(date + "{date}".length())) + "(\\.gz)?";
    }

    /**
     * Builds a backup's file name.
     *
     * @param when when the backup is taken
     * @param compressed whether to add {@code .gz}
     * @return the file name
     */
    public String fileName(LocalDateTime when, boolean compressed) {
        return template.replace("{date}", STAMP.format(when)) + (compressed ? ".gz" : "");
    }

    /**
     * Picks out the backups from a list of file names.
     *
     * @param names file names; anything unrecognised is ignored
     * @return the backups, newest first
     */
    public List<BackupEntry> entries(Iterable<String> names) {
        List<BackupEntry> entries = new ArrayList<>();
        for (String name : names) {
            Matcher match = matcher.matcher(name);
            if (!match.matches()) continue;
            try {
                entries.add(new BackupEntry(name, LocalDateTime.parse(match.group(1), STAMP)));
            } catch (DateTimeParseException e) {
                // Looks like a backup, but isn't a real date: leave it alone.
            }
        }
        entries.sort(Comparator.comparing(BackupEntry::createdAt).reversed());
        return entries;
    }
}
