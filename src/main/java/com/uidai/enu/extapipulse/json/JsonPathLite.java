package com.uidai.enu.extapipulse.json;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The small JSONPath subset health checks need, on top of Jackson:
 * {@code $}, {@code $.a.b}, {@code $.items[0].id}, {@code $['odd-key'].x}.
 * No wildcards or filters; a probe should point at one exact field.
 */
public final class JsonPathLite {

    private JsonPathLite() {}

    public static Optional<JsonNode> read(JsonNode root, String path) {
        JsonNode current = root;
        for (Object step : parse(path)) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return Optional.empty();
            }
            current = step instanceof Integer index ? current.get(index) : current.get((String) step);
        }
        return current == null || current.isMissingNode() ? Optional.empty() : Optional.of(current);
    }

    /** Value as text: strings unquoted, everything else as JSON. */
    public static Optional<String> readText(JsonNode root, String path) {
        return read(root, path).map(n -> n.isValueNode() ? n.asString() : n.toString());
    }

    public static void validate(String path) {
        parse(path);
    }

    static List<Object> parse(String path) {
        if (path == null || !path.startsWith("$")) {
            throw new IllegalArgumentException("JSON path must start with '$': " + path);
        }
        List<Object> steps = new ArrayList<>();
        int i = 1;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                int start = ++i;
                while (i < path.length() && path.charAt(i) != '.' && path.charAt(i) != '[') {
                    i++;
                }
                if (start == i) {
                    throw new IllegalArgumentException("Empty field name in JSON path: " + path);
                }
                steps.add(path.substring(start, i));
            } else if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) {
                    throw new IllegalArgumentException("Unclosed '[' in JSON path: " + path);
                }
                String inner = path.substring(i + 1, close).trim();
                if (inner.length() >= 2 && (inner.startsWith("'") && inner.endsWith("'")
                        || inner.startsWith("\"") && inner.endsWith("\""))) {
                    steps.add(inner.substring(1, inner.length() - 1));
                } else {
                    try {
                        steps.add(Integer.parseInt(inner));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Bad index '" + inner + "' in JSON path: " + path);
                    }
                }
                i = close + 1;
            } else {
                throw new IllegalArgumentException("Unexpected '" + c + "' at position " + i + " in JSON path: " + path);
            }
        }
        return steps;
    }
}
