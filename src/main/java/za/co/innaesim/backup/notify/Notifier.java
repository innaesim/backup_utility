package za.co.innaesim.backup.notify;

import za.co.innaesim.backup.service.RunReport;

/** Tells someone how a run went. Implementations must not throw: a failed alert can't fail a backup. */
@FunctionalInterface
public interface Notifier {
    /** A notifier that does nothing. */
    Notifier NONE = report -> { };

    /**
     * Sends the result of a run.
     *
     * @param report what happened
     */
    void send(RunReport report);
}
