package com.uidai.enu.extapipulse.template;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers every secret value that passed through a template (env secrets, tokens,
 * session ids) and blanks them out of anything we log or email.
 */
public class SecretMasker {

    private static final int MIN_LENGTH = 4;
    private static final String MASK = "****";

    private final Set<String> secrets = ConcurrentHashMap.newKeySet();

    public void register(String value) {
        if (value != null && value.length() >= MIN_LENGTH) {
            secrets.add(value);
        }
    }

    public String mask(String text) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) {
            return text;
        }
        // Longest first, so a secret that contains another is masked whole.
        List<String> ordered = secrets.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        String out = text;
        for (String secret : ordered) {
            out = out.replace(secret, MASK);
        }
        return out;
    }
}
