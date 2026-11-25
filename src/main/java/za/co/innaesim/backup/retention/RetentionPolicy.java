package za.co.innaesim.backup.retention;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import za.co.innaesim.backup.model.RetentionConfig;

/** Decides which backups to delete. */
public final class RetentionPolicy {
    private RetentionPolicy() {}

    /**
     * A backup survives if it is the newest, within {@code days}, or among the newest {@code keepLast}.
     *
     * @param newestFirst backups sorted newest first, as {@link BackupNaming#entries} returns them
     * @param policy the rules to apply; may be null
     * @param now the current time
     * @return names to delete; empty when no policy is set
     */
    public static List<String> toDelete(List<BackupEntry> newestFirst, RetentionConfig policy, LocalDateTime now) {
        List<String> doomed = new ArrayList<>();
        if (policy == null || (policy.days() == null && policy.keepLast() == null)) return doomed;
        LocalDateTime cutoff = policy.days() != null ? now.minusDays(policy.days()) : null;
        int keepLast = policy.keepLast() != null ? policy.keepLast() : 0;
        for (int i = 1; i < newestFirst.size(); i++) { // index 0, the newest, always stays
            BackupEntry entry = newestFirst.get(i);
            boolean withinDays = cutoff != null && !entry.createdAt().isBefore(cutoff);
            boolean withinCount = i < keepLast;
            if (!withinDays && !withinCount) doomed.add(entry.name());
        }
        return doomed;
    }
}
