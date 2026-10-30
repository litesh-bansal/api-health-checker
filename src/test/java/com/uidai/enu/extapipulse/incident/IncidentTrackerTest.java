package com.uidai.enu.extapipulse.incident;

import com.uidai.enu.extapipulse.model.AlertingPolicy;
import com.uidai.enu.extapipulse.model.CheckResult;
import com.uidai.enu.extapipulse.model.FailureCategory;
import com.uidai.enu.extapipulse.model.ProbeKey;
import com.uidai.enu.extapipulse.model.Route;
import com.uidai.enu.extapipulse.model.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentTrackerTest {

    private static final ProbeKey KEY = new ProbeKey("MAHARASHTRA", "DL");
    private static final Route VENDOR = new Route(List.of("dev"), List.of(), true, List.of("email"));
    private static final Route INFRA = new Route(List.of("infra"), List.of(), false, List.of("email"));

    private InMemoryIncidentStore store;
    private IncidentTracker tracker;
    private Instant now;

    // threshold 3, recovery 2, reminders every 60 min doubling up to 240, unlimited
    private final AlertingPolicy policy = new AlertingPolicy(3, 2, Duration.ofMinutes(60), 2.0,
            Duration.ofMinutes(240), 0, true);

    @BeforeEach
    void setUp() {
        store = new InMemoryIncidentStore();
        tracker = new IncidentTracker(store, c -> switch (c) {
            case PROXY, DNS, NETWORK -> INFRA;
            default -> VENDOR;
        });
        now = Instant.parse("2026-10-30T05:00:00Z");
    }

    @Test
    void noAlertUntilFailureThresholdReached() {
        assertTrue(fail(FailureCategory.VENDOR_DOWN).isEmpty());
        assertTrue(fail(FailureCategory.VENDOR_DOWN).isEmpty());
        Optional<AlertEvent> third = fail(FailureCategory.VENDOR_DOWN);
        assertTrue(third.isPresent());
        assertEquals(AlertEvent.Type.DOWN, third.get().type());
        assertEquals(Instant.parse("2026-10-30T05:00:00Z"), third.get().downSince(), "down since = first failure");
    }

    @Test
    void singlePassBeforeThresholdResetsTheStreak() {
        fail(FailureCategory.VENDOR_DOWN);
        fail(FailureCategory.VENDOR_DOWN);
        pass();
        assertTrue(fail(FailureCategory.VENDOR_DOWN).isEmpty());
        assertTrue(fail(FailureCategory.VENDOR_DOWN).isEmpty());
        assertTrue(fail(FailureCategory.VENDOR_DOWN).isPresent());
    }

    @Test
    void remindersAreThrottledAndBackOff() {
        goDown();
        // every 5 minutes for the next 55 minutes: silence
        for (int i = 0; i < 11; i++) {
            assertTrue(fail(FailureCategory.VENDOR_DOWN).isEmpty(), "no spam inside the interval");
        }
        Optional<AlertEvent> r1 = fail(FailureCategory.VENDOR_DOWN); // 60 min after DOWN
        assertEquals(AlertEvent.Type.REMINDER, r1.orElseThrow().type());
        assertEquals(1, r1.get().reminderNumber());

        // next interval doubled to 120 min
        int silent = 0;
        Optional<AlertEvent> r2 = Optional.empty();
        while (r2.isEmpty()) {
            r2 = fail(FailureCategory.VENDOR_DOWN);
            if (r2.isEmpty()) silent++;
        }
        assertEquals(23, silent, "120 min / 5 min checks = 24th check fires");
        assertEquals(2, r2.get().reminderNumber());
    }

    @Test
    void backoffIsCappedAtMaxReminderInterval() {
        Instant last = goDown().occurredAt();
        List<Long> gaps = new java.util.ArrayList<>();
        while (gaps.size() < 4) {
            Optional<AlertEvent> e = fail(FailureCategory.VENDOR_DOWN);
            if (e.isPresent()) {
                gaps.add(Duration.between(last, e.get().occurredAt()).toMinutes());
                last = e.get().occurredAt();
            }
        }
        assertEquals(List.of(60L, 120L, 240L, 240L), gaps);
    }

    @Test
    void maxRemindersStopsReminders() {
        AlertingPolicy limited = new AlertingPolicy(1, 1, Duration.ofMinutes(10), 1.0,
                Duration.ofMinutes(10), 2, true);
        assertEquals(AlertEvent.Type.DOWN, tracker.record(KEY, limited, failed(FailureCategory.VENDOR_DOWN))
                .orElseThrow().type());
        int reminders = 0;
        AlertEvent lastReminder = null;
        for (int i = 0; i < 50; i++) {
            now = now.plus(Duration.ofMinutes(5));
            Optional<AlertEvent> e = tracker.record(KEY, limited, failed(FailureCategory.VENDOR_DOWN));
            if (e.isPresent()) {
                reminders++;
                lastReminder = e.get();
            }
        }
        assertEquals(2, reminders);
        assertTrue(lastReminder.lastReminder());
    }

    @Test
    void causeChangeToDifferentPeopleAlertsImmediately() {
        goDown();
        Optional<AlertEvent> e = fail(FailureCategory.PROXY);
        assertEquals(AlertEvent.Type.CATEGORY_CHANGED, e.orElseThrow().type());
        assertEquals(FailureCategory.VENDOR_DOWN, e.get().previousCategory());
        assertEquals(FailureCategory.PROXY, e.get().category());
    }

    @Test
    void causeChangeToSamePeopleDoesNotAlert() {
        goDown();
        // VENDOR_DOWN and CONTRACT route to the same people: no extra email
        assertTrue(fail(FailureCategory.CONTRACT).isEmpty());
        assertTrue(fail(FailureCategory.VENDOR_DOWN).isEmpty());
    }

    @Test
    void recoveryNeedsConsecutivePassesAndResetsEverything() {
        goDown();
        tracker.addNotified(KEY, Set.of("dev@ours.com", "vendor@state.gov.in"));

        assertTrue(pass().isEmpty(), "one pass is not a recovery");
        assertEquals(IncidentStatus.RECOVERING, status());

        // flapping: fails again -> back to DOWN, same incident, no new DOWN email
        assertTrue(fail(FailureCategory.VENDOR_DOWN).isEmpty());
        assertEquals(IncidentStatus.DOWN, status());

        assertTrue(pass().isEmpty());
        Optional<AlertEvent> rec = pass();
        assertEquals(AlertEvent.Type.RECOVERED, rec.orElseThrow().type());
        assertEquals(Set.of("dev@ours.com", "vendor@state.gov.in"), rec.get().previouslyNotified());
        assertEquals(IncidentStatus.UP, status());

        // timers reset: the next outage needs the full threshold again and alerts as a fresh DOWN
        assertTrue(fail(FailureCategory.VENDOR_DOWN).isEmpty());
        assertTrue(fail(FailureCategory.VENDOR_DOWN).isEmpty());
        assertEquals(AlertEvent.Type.DOWN, fail(FailureCategory.VENDOR_DOWN).orElseThrow().type());
    }

    @Test
    void suppressedFailuresDoNotCount() {
        fail(FailureCategory.VENDOR_DOWN);
        fail(FailureCategory.VENDOR_DOWN);
        for (int i = 0; i < 10; i++) {
            now = now.plus(Duration.ofMinutes(5));
            tracker.recordSuppressed(KEY, failed(FailureCategory.PROXY));
        }
        assertEquals(IncidentStatus.SUSPECT, status());
        assertTrue(store.find(KEY).orElseThrow().snapshot().lastFailureBlamedOnOurNetwork());
        assertFalse(fail(FailureCategory.VENDOR_DOWN).isEmpty(), "third real failure still declares DOWN");
    }

    // ------------------------------------------------------------ helpers

    private AlertEvent goDown() {
        fail(FailureCategory.VENDOR_DOWN);
        fail(FailureCategory.VENDOR_DOWN);
        AlertEvent down = fail(FailureCategory.VENDOR_DOWN).orElseThrow();
        assertEquals(AlertEvent.Type.DOWN, down.type());
        return down;
    }

    private Optional<AlertEvent> fail(FailureCategory c) {
        Optional<AlertEvent> e = tracker.record(KEY, policy, failed(c));
        now = now.plus(Duration.ofMinutes(5));
        return e;
    }

    private Optional<AlertEvent> pass() {
        Optional<AlertEvent> e = tracker.record(KEY, policy, CheckResult.ok(KEY, now, 200, 50));
        now = now.plus(Duration.ofMinutes(5));
        return e;
    }

    private CheckResult failed(FailureCategory c) {
        return CheckResult.failed(KEY, now, c, Stage.MAIN, 503, 40, "test");
    }

    private IncidentStatus status() {
        return store.find(KEY).orElseThrow().snapshot().status();
    }
}
