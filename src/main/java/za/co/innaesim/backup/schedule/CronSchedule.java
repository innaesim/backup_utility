package za.co.innaesim.backup.schedule;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

/** A standard five-field cron expression: minute, hour, day of month, month, day of week. */
public final class CronSchedule {
    private static final CronParser PARSER = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    private final ExecutionTime time;

    private CronSchedule(ExecutionTime time) {
        this.time = time;
    }

    /**
     * Parses a cron expression.
     *
     * @param expression the five-field expression
     * @return the schedule
     * @throws IllegalArgumentException if the expression isn't valid
     */
    public static CronSchedule parse(String expression) {
        try {
            return new CronSchedule(ExecutionTime.forCron(PARSER.parse(expression.strip())));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + expression + "' isn't a valid 5-field cron expression: " + e.getMessage());
        }
    }

    /**
     * Tells whether the schedule is due in a given minute.
     *
     * @param when the time to check; seconds are ignored
     * @return true if it's due
     */
    public boolean matches(ZonedDateTime when) {
        return time.isMatch(when.truncatedTo(ChronoUnit.MINUTES));
    }

    /**
     * Finds the next time the schedule is due.
     *
     * @param when the time to start from
     * @return the next run, or empty if there is none
     */
    public Optional<ZonedDateTime> nextAfter(ZonedDateTime when) {
        return time.nextExecution(when);
    }
}
