package za.co.innaesim.backup.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands ${VAR} in config values. Real environment variables win over the env file, which exists
 * because services started at boot don't inherit a login shell's environment.
 */
public final class EnvResolver {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private EnvResolver() {}

    /**
     * Builds the standard lookup: the real environment first, then the env file.
     *
     * @param envFile the env file; may be missing
     * @return a lookup returning null for unknown names
     * @throws ConfigException if the env file exists but can't be read
     */
    public static Function<String, String> system(Path envFile) {
        Map<String, String> fromFile = readEnvFile(envFile);
        return key -> {
            String value = System.getenv(key);
            return value != null ? value : fromFile.get(key);
        };
    }

    /**
     * Replaces each {@code ${VAR}} in a value. Unknown variables are reported and become empty.
     *
     * @param value the text to expand
     * @param env looks up values, returning null for unknown names
     * @param onMissing receives the name of each unknown variable
     * @return the expanded text
     */
    public static String expand(String value, Function<String, String> env, java.util.function.Consumer<String> onMissing) {
        Matcher matcher = VARIABLE.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String resolved = env.apply(matcher.group(1));
            if (resolved == null) {
                onMissing.accept(matcher.group(1));
                resolved = "";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static Map<String, String> readEnvFile(Path file) {
        Map<String, String> values = new HashMap<>();
        if (file == null || !Files.isRegularFile(file)) return values;
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring(7).strip();
                int equals = line.indexOf('=');
                if (equals <= 0) continue;
                String value = line.substring(equals + 1).strip();
                if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                        && value.charAt(value.length() - 1) == value.charAt(0)) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(line.substring(0, equals).strip(), value);
            }
        } catch (IOException e) {
            throw new ConfigException("Can't read env file " + file + ": " + e.getMessage());
        }
        return values;
    }
}
