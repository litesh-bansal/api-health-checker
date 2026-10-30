package com.uidai.enu.extapipulse.incident;

import com.uidai.enu.extapipulse.model.ProbeKey;

import java.util.Collection;
import java.util.Optional;

/**
 * Where incident state lives. The in-memory version forgets everything on restart,
 * which re-sends a DOWN email for anything still failing. For production, implement this
 * over a DB table or Redis keyed by "source:docId" and call {@link #save} after each change.
 */
public interface IncidentStore {

    IncidentState getOrCreate(ProbeKey key);

    Optional<IncidentState> find(ProbeKey key);

    Collection<IncidentState> all();

    /** Called after every change while the state's lock is held. No-op in memory. */
    void save(IncidentState state);
}
