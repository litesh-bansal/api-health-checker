package com.uidai.enu.extapipulse.probe;

import com.uidai.enu.extapipulse.config.ProbeCatalog;
import com.uidai.enu.extapipulse.config.ProbeCatalog.ControlCheck;
import com.uidai.enu.extapipulse.model.ProbeDefinition;
import com.uidai.enu.extapipulse.model.ProbeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fires every probe at its own interval, plus each control check.
 *
 * One small timer thread only triggers; each check runs on its own virtual thread, so a slow
 * API (timeouts + retries) never delays the others. If a probe is still running when its next
 * turn comes, that turn is skipped instead of piling up. Start times are spread out so many
 * probes do not all fire in the same second.
 */
public class ProbeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProbeScheduler.class);
    private static final long SPREAD_MS = 30_000;
    private static final long CONTROL_HEAD_START_MS = 5_000;

    private final ProbeCatalog catalog;
    private final ProbeRunner runner;
    private final boolean enabled;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pulse-timer");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<ProbeKey, AtomicBoolean> running = new ConcurrentHashMap<>();

    public ProbeScheduler(ProbeCatalog catalog, ProbeRunner runner, boolean enabled) {
        this.catalog = catalog;
        this.runner = runner;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.warn("pulse.enabled=false - probes are not scheduled (manual checks via the REST endpoint still work)");
            return;
        }
        for (ControlCheck c : catalog.controlChecks()) {
            ProbeKey key = ProbeKey.control(c.proxy());
            every(key, Duration.ofSeconds(1), c.interval(), () -> runner.runControlAndRecord(c));
            log.info("Scheduled control check '{}' every {}s", c.proxy(), c.interval().toSeconds());
        }
        for (ProbeDefinition def : catalog.probes()) {
            Duration offset = Duration.ofMillis(CONTROL_HEAD_START_MS + Math.floorMod(def.key().hashCode(), SPREAD_MS));
            every(def.key(), offset, def.interval(), () -> runner.runAndRecord(def));
            log.info("Scheduled {} every {}s via proxy '{}' (auth: {})",
                    def.key(), def.interval().toSeconds(), def.proxy(), def.authProfile());
        }
    }

    private void every(ProbeKey key, Duration initialDelay, Duration interval, Runnable task) {
        AtomicBoolean busy = running.computeIfAbsent(key, k -> new AtomicBoolean());
        timer.scheduleAtFixedRate(() -> {
            if (!busy.compareAndSet(false, true)) {
                log.warn("{} is still running from its previous turn - skipping this one", key);
                return;
            }
            try {
                workers.execute(() -> {
                    try {
                        task.run();
                    } finally {
                        busy.set(false);
                    }
                });
            } catch (RuntimeException e) { // executor shut down
                busy.set(false);
            }
        }, initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        timer.shutdownNow();
        workers.shutdownNow();
    }
}
