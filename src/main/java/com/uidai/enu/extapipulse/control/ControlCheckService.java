package com.uidai.enu.extapipulse.control;

import com.uidai.enu.extapipulse.classify.FailureClassifier;
import com.uidai.enu.extapipulse.classify.FailureClassifier.Classification;
import com.uidai.enu.extapipulse.config.ProbeCatalog;
import com.uidai.enu.extapipulse.config.ProbeCatalog.ControlCheck;
import com.uidai.enu.extapipulse.http.HttpCall;
import com.uidai.enu.extapipulse.http.HttpExecutor;
import com.uidai.enu.extapipulse.http.HttpReply;
import com.uidai.enu.extapipulse.model.CheckResult;
import com.uidai.enu.extapipulse.model.FailureCategory;
import com.uidai.enu.extapipulse.model.ProbeKey;
import com.uidai.enu.extapipulse.model.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Calls always-up reference URLs through the same route (proxy) as the probes.
 * If none of them answers, our own network/proxy is broken and probe failures on that
 * route are not the vendor's fault.
 */
public class ControlCheckService {

    private static final Logger log = LoggerFactory.getLogger(ControlCheckService.class);

    private final ProbeCatalog catalog;
    private final HttpExecutor executor;
    private final FailureClassifier classifier;
    private final Clock clock;
    private final Map<String, CheckResult> latest = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ControlCheckService(ProbeCatalog catalog, HttpExecutor executor,
                               FailureClassifier classifier, Clock clock) {
        this.catalog = catalog;
        this.executor = executor;
        this.classifier = classifier;
        this.clock = clock;
    }

    /**
     * Is the route healthy? Uses a recent result if one exists, otherwise checks now.
     * Empty when no control check is configured for this proxy.
     */
    public Optional<CheckResult> verdict(String proxy) {
        ControlCheck check = catalog.controlCheck(proxy);
        if (check == null) {
            return Optional.empty();
        }
        CheckResult cached = latest.get(proxy);
        if (cached != null && isFresh(cached, check.cacheFor())) {
            return Optional.of(cached);
        }
        ReentrantLock lock = locks.computeIfAbsent(proxy, k -> new ReentrantLock());
        lock.lock();
        try {
            // Another thread may have refreshed it while we waited.
            cached = latest.get(proxy);
            if (cached != null && isFresh(cached, check.cacheFor())) {
                return Optional.of(cached);
            }
            return Optional.of(runNow(check));
        } finally {
            lock.unlock();
        }
    }

    /** Always performs the check (used by the scheduler). */
    public CheckResult runNow(ControlCheck check) {
        ProbeKey key = ProbeKey.control(check.proxy());
        Instant at = clock.instant();
        Classification lastFailure = null;
        for (String url : check.urls()) {
            try {
                HttpReply reply = executor.execute(
                        new HttpCall("GET", url, Map.of(), null, check.timeout()), check.proxy());
                // Any HTTP answer at all proves the route works.
                CheckResult ok = CheckResult.ok(key, at, reply.status(), reply.latencyMs());
                latest.put(check.proxy(), ok);
                return ok;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastFailure = new Classification(FailureCategory.NETWORK, "Interrupted", null);
                break;
            } catch (Exception e) {
                lastFailure = classifier.classifyException(e, Stage.CONTROL, !ProbeCatalog.NONE.equals(check.proxy()));
                log.debug("Control URL {} via {} failed: {}", url, check.proxy(), lastFailure.detail());
            }
        }
        CheckResult failed = CheckResult.failed(key, at, lastFailure.category(), Stage.CONTROL,
                lastFailure.httpStatus(), 0, "All reference URLs failed via '" + check.proxy() + "': "
                        + lastFailure.detail());
        latest.put(check.proxy(), failed);
        return failed;
    }

    public Map<String, CheckResult> latestResults() {
        return Map.copyOf(latest);
    }

    private boolean isFresh(CheckResult r, Duration maxAge) {
        return Duration.between(r.at(), clock.instant()).compareTo(maxAge) < 0;
    }
}
