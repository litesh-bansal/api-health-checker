package com.uidai.enu.extapipulse.incident;

import com.uidai.enu.extapipulse.model.ProbeKey;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryIncidentStore implements IncidentStore {

    private final Map<ProbeKey, IncidentState> states = new ConcurrentHashMap<>();

    @Override
    public IncidentState getOrCreate(ProbeKey key) {
        return states.computeIfAbsent(key, IncidentState::new);
    }

    @Override
    public Optional<IncidentState> find(ProbeKey key) {
        return Optional.ofNullable(states.get(key));
    }

    @Override
    public Collection<IncidentState> all() {
        return List.copyOf(states.values());
    }

    @Override
    public void save(IncidentState state) {
        // Nothing to do: the map holds the live object.
    }
}
