package za.co.innaesim.backup.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** One block of YAML. Reads typed values, expands ${VAR}, and records problems instead of throwing. */
final class Section {
    private final String path;
    private final Map<String, Object> values;
    private final Function<String, String> env;
    private final List<String> problems;
    private final Set<String> read = new HashSet<>();

    Section(String path, Map<String, Object> values, Function<String, String> env, List<String> problems) {
        this.path = path;
        this.values = values;
        this.env = env;
        this.problems = problems;
    }

    String path() {
        return path;
    }

    boolean has(String key) {
        return values.containsKey(key);
    }

    String string(String key, String fallback) {
        read.add(key);
        Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Map || value instanceof Collection) {
            problems.add(path + "." + key + " must be a single value");
            return fallback;
        }
        return expand(key, String.valueOf(value));
    }

    String requiredString(String key) {
        String value = string(key, null);
        if (value == null || value.isBlank()) {
            problems.add(path + "." + key + " is required");
            return null;
        }
        return value;
    }

    Integer integer(String key) {
        String value = string(key, null);
        if (value == null) return null;
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            problems.add(path + "." + key + " must be a whole number, got '" + value + "'");
            return null;
        }
    }

    boolean bool(String key, boolean fallback) {
        String value = string(key, null);
        if (value == null) return fallback;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        problems.add(path + "." + key + " must be true or false, got '" + value + "'");
        return fallback;
    }

    List<String> stringList(String key) {
        read.add(key);
        Object value = values.get(key);
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        if (!(value instanceof Collection)) {
            problems.add(path + "." + key + " must be a list, like [a, b]");
            return out;
        }
        for (Object item : (Collection<?>) value) out.add(expand(key, String.valueOf(item)));
        return out;
    }

    /** A nested block, or null when it's absent. */
    Section child(String key) {
        read.add(key);
        Object value = values.get(key);
        if (value == null) return null;
        if (!(value instanceof Map)) {
            problems.add(path + "." + key + " must be a group of settings");
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((k, v) -> copy.put(String.valueOf(k), v));
        return new Section(path + "." + key, copy, env, problems);
    }

    /** Catches typos such as "retentin", which would otherwise silently disable a feature. */
    void rejectUnknownKeys() {
        for (String key : values.keySet()) {
            if (!read.contains(key)) problems.add("Unknown setting " + path + "." + key);
        }
    }

    private String expand(String key, String value) {
        return EnvResolver.expand(value, env,
                missing -> problems.add(path + "." + key + " uses ${" + missing + "}, which isn't set"));
    }
}
