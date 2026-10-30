package com.uidai.enu.extapipulse.template;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills {{placeholders}} in URLs, headers, query values and bodies.
 *
 * <ul>
 *   <li>{{env.NAME}}        - secret/config value from the Spring Environment (env var, system prop, vault)</li>
 *   <li>{{auth.NAME}}       - value extracted from the login/token call</li>
 *   <li>{{uuid}}            - random UUID (for APIs that need a unique transaction id)</li>
 *   <li>{{now.epochMillis}}, {{now.epochSeconds}}, {{now.iso}} - current time</li>
 * </ul>
 *
 * Values are escaped for where they land: JSON-escaped in JSON bodies,
 * URL-encoded in form bodies, raw elsewhere. env and auth values are registered
 * with the {@link SecretMasker} so they never appear in logs or emails.
 */
public class TemplateRenderer {

    public enum Escape { NONE, JSON, URL }

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([A-Za-z]+)(?:\\.([A-Za-z0-9_.\\-]+))?\\s*}}");

    private final Function<String, String> envLookup;
    private final SecretMasker masker;
    private final Clock clock;

    public TemplateRenderer(Function<String, String> envLookup, SecretMasker masker, Clock clock) {
        this.envLookup = envLookup;
        this.masker = masker;
        this.clock = clock;
    }

    public String render(String template, Map<String, String> authVars, Escape escape) {
        if (template == null || !template.contains("{{")) {
            return template;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = resolve(m.group(1), m.group(2), authVars);
            m.appendReplacement(out, Matcher.quoteReplacement(escape(value, escape)));
        }
        m.appendTail(out);
        return out.toString();
    }

    public SecretMasker masker() {
        return masker;
    }

    private String resolve(String namespace, String key, Map<String, String> authVars) {
        switch (namespace.toLowerCase(Locale.ROOT)) {
            case "env" -> {
                requireKey(namespace, key);
                String value = envLookup.apply(key);
                if (value == null) {
                    throw new ConfigException("Missing value for {{env." + key + "}}");
                }
                masker.register(value);
                return value;
            }
            case "auth" -> {
                requireKey(namespace, key);
                String value = authVars == null ? null : authVars.get(key);
                if (value == null) {
                    throw new ConfigException("{{auth." + key + "}} used, but the auth profile did not extract '" + key + "'");
                }
                masker.register(value);
                return value;
            }
            case "uuid" -> {
                return UUID.randomUUID().toString();
            }
            case "now" -> {
                Instant now = clock.instant();
                String k = key == null ? "iso" : key;
                return switch (k) {
                    case "epochMillis" -> Long.toString(now.toEpochMilli());
                    case "epochSeconds" -> Long.toString(now.getEpochSecond());
                    case "iso" -> now.toString();
                    default -> throw new ConfigException("Unknown placeholder {{now." + k + "}}");
                };
            }
            default -> throw new ConfigException("Unknown placeholder namespace '" + namespace + "'");
        }
    }

    private static void requireKey(String namespace, String key) {
        if (key == null || key.isBlank()) {
            throw new ConfigException("Placeholder {{" + namespace + "}} needs a name, e.g. {{" + namespace + ".NAME}}");
        }
    }

    private static String escape(String value, Escape escape) {
        return switch (escape) {
            case NONE -> value;
            case URL -> URLEncoder.encode(value, StandardCharsets.UTF_8);
            case JSON -> jsonEscape(value);
        };
    }

    static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    /** Escaping for a request body, decided by its Content-Type header. */
    public static Escape escapeForContentType(Map<String, String> headers) {
        if (headers == null) {
            return Escape.NONE;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(e.getKey()) && e.getValue() != null) {
                String ct = e.getValue().toLowerCase(Locale.ROOT);
                if (ct.contains("json")) {
                    return Escape.JSON;
                }
                if (ct.contains("x-www-form-urlencoded")) {
                    return Escape.URL;
                }
            }
        }
        return Escape.NONE;
    }

    /** Names used with a namespace in a template, e.g. all NAME in {{env.NAME}}. Used for startup validation. */
    public static Set<String> referencedKeys(String template, String namespace) {
        Set<String> keys = new LinkedHashSet<>();
        if (template == null) {
            return keys;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            if (namespace.equalsIgnoreCase(m.group(1)) && m.group(2) != null) {
                keys.add(m.group(2));
            }
        }
        return keys;
    }
}
