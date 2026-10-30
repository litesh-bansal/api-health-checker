package com.uidai.enu.extapipulse.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Values extracted by a login call, kept until they expire. Key = profile name + proxy. */
public class TokenCache {

    private record Entry(Map<String, String> vars, Instant expiresAt) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public TokenCache(Clock clock) {
        this.clock = clock;
    }

    public Optional<Map<String, String>> get(String key) {
        Entry e = entries.get(key);
        if (e == null || !clock.instant().isBefore(e.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(e.vars());
    }

    public void put(String key, Map<String, String> vars, Instant expiresAt) {
        entries.put(key, new Entry(Map.copyOf(vars), expiresAt));
    }

    public void invalidate(String key) {
        entries.remove(key);
    }
}
