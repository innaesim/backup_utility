package za.co.innaesim.backup.config;

import java.util.List;

/** The configuration can't be used. Carries every problem found, not just the first. */
public class ConfigException extends RuntimeException {
    private final transient List<String> problems;

    /**
     * Creates an exception reporting several problems.
     *
     * @param problems what is wrong, one entry per problem
     */
    public ConfigException(List<String> problems) {
        super("Invalid configuration:\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    /**
     * Creates an exception reporting one problem.
     *
     * @param problem what is wrong
     */
    public ConfigException(String problem) {
        this(List.of(problem));
    }

    /**
     * Lists what is wrong.
     *
     * @return the problems found, one entry per problem
     */
    public List<String> getProblems() {
        return problems;
    }
}
