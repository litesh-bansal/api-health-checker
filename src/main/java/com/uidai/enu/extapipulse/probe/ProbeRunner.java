package com.uidai.enu.extapipulse.probe;

import com.uidai.enu.extapipulse.alert.AlertDispatcher;
import com.uidai.enu.extapipulse.auth.AuthContext;
import com.uidai.enu.extapipulse.auth.AuthResolver;
import com.uidai.enu.extapipulse.auth.AuthStepException;
import com.uidai.enu.extapipulse.classify.ExpectationCheck;
import com.uidai.enu.extapipulse.classify.FailureClassifier;
import com.uidai.enu.extapipulse.classify.FailureClassifier.Classification;
import com.uidai.enu.extapipulse.config.ProbeCatalog;
import com.uidai.enu.extapipulse.config.ProbeCatalog.ControlCheck;
import com.uidai.enu.extapipulse.control.ControlCheckService;
import com.uidai.enu.extapipulse.http.CallBuilder;
import com.uidai.enu.extapipulse.http.HttpCall;
import com.uidai.enu.extapipulse.http.HttpExecutor;
import com.uidai.enu.extapipulse.http.HttpReply;
import com.uidai.enu.extapipulse.incident.IncidentTracker;
import com.uidai.enu.extapipulse.model.AuthProfile;
import com.uidai.enu.extapipulse.model.CheckResult;
import com.uidai.enu.extapipulse.model.FailureCategory;
import com.uidai.enu.extapipulse.model.ProbeDefinition;
import com.uidai.enu.extapipulse.model.ProbeKey;
import com.uidai.enu.extapipulse.model.Stage;
import com.uidai.enu.extapipulse.template.SecretMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Runs one probe end to end:
 * login (if any) -> main call -> evaluate -> retries -> control check on failure -> state machine -> alerts.
 */
public class ProbeRunner {

    private static final Logger log = LoggerFactory.getLogger(ProbeRunner.class);

    private final ProbeCatalog catalog;
    private final AuthResolver auth;
    private final CallBuilder callBuilder;
    private final HttpExecutor executor;
    private final ExpectationCheck expectationCheck;
    private final FailureClassifier classifier;
    private final ControlCheckService control;
    private final IncidentTracker tracker;
    private final AlertDispatcher dispatcher;
    private final SecretMasker masker;
    private final Clock clock;

    public ProbeRunner(ProbeCatalog catalog, AuthResolver auth, CallBuilder callBuilder, HttpExecutor executor,
                       ExpectationCheck expectationCheck, FailureClassifier classifier,
                       ControlCheckService control, IncidentTracker tracker, AlertDispatcher dispatcher,
                       SecretMasker masker, Clock clock) {
        this.catalog = catalog;
        this.auth = auth;
        this.callBuilder = callBuilder;
        this.executor = executor;
        this.expectationCheck = expectationCheck;
        this.classifier = classifier;
        this.control = control;
        this.tracker = tracker;
        this.dispatcher = dispatcher;
        this.masker = masker;
        this.clock = clock;
    }

    /** Scheduled entry point. Never throws, so the schedule keeps running. */
    public void runAndRecord(ProbeDefinition def) {
        try {
            CheckResult result = check(def);
            if (result.success()) {
                log.debug("{} OK in {} ms", def.key(), result.latencyMs());
            } else {
                Optional<CheckResult> route = control.verdict(def.proxy());
                if (route.isPresent() && !route.get().success()) {
                    log.warn("{} failed ({}), but the control check via '{}' also failed ({}) - "
                                    + "blaming our network, not alerting for this API",
                            def.key(), result.category(), def.proxy(), route.get().category());
                    tracker.recordSuppressed(def.key(), result);
                    return;
                }
                log.warn("{} failed: {} at {} - {}", def.key(), result.category(), result.stage(), result.detail());
            }
            tracker.record(def.key(), def.alerting(), result)
                    .ifPresent(event -> dispatcher.dispatch(event, def.contacts()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("Bug while running probe {}: {}", def.key(), e.getMessage(), e);
        }
    }

    /** Scheduled entry point for a control check; it raises its own incident when our network is down. */
    public void runControlAndRecord(ControlCheck check) {
        try {
            CheckResult result = control.runNow(check);
            ProbeKey key = ProbeKey.control(check.proxy());
            tracker.record(key, catalog.globalAlerting(), result)
                    .ifPresent(event -> dispatcher.dispatch(event, List.of()));
        } catch (RuntimeException e) {
            log.error("Bug while running control check '{}': {}", check.proxy(), e.getMessage(), e);
        }
    }

    /** Runs the probe with retries and returns the final outcome. No alerts, no state changes. */
    public CheckResult check(ProbeDefinition def) throws InterruptedException {
        CheckResult result = attempt(def);
        for (int retry = 1; retry <= def.retries() && !result.success()
                && classifier.isRetryable(result.category()); retry++) {
            Thread.sleep(def.retryBackoff().multipliedBy(retry).toMillis());
            log.debug("{} retry {}/{} after {}", def.key(), retry, def.retries(), result.category());
            result = attempt(def);
        }
        return result;
    }

    private CheckResult attempt(ProbeDefinition def) throws InterruptedException {
        AuthProfile profile = catalog.authProfile(def.authProfile());
        Instant at = clock.instant();

        Outcome<AuthContext> login = login(def, profile, false, at);
        if (login.failure != null) {
            return login.failure;
        }
        CheckResult result = mainCall(def, login.value, at);

        // A cached token may have been revoked early: log in again once and retry.
        if (!result.success() && result.category() == FailureCategory.AUTH && login.value.fromCache()) {
            log.info("{} got {} with a cached token - logging in again", def.key(), result.httpStatus());
            auth.invalidate(profile, def.proxy());
            login = login(def, profile, true, at);
            if (login.failure != null) {
                return login.failure;
            }
            result = mainCall(def, login.value, at);
        }
        return result;
    }

    private Outcome<AuthContext> login(ProbeDefinition def, AuthProfile profile, boolean force, Instant at)
            throws InterruptedException {
        try {
            return Outcome.ok(auth.resolve(profile, def.proxy(), def.timeout(), force));
        } catch (AuthStepException e) {
            Classification c = classifier.classifyAuthStep(e);
            return Outcome.failed(fail(def.key(), at, c, Stage.AUTH, e.reply().latencyMs()));
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            Classification c = classifier.classifyException(e, Stage.AUTH, viaProxy(def));
            return Outcome.failed(fail(def.key(), at, c, Stage.AUTH, 0));
        }
    }

    private CheckResult mainCall(ProbeDefinition def, AuthContext ctx, Instant at) throws InterruptedException {
        try {
            HttpCall call = callBuilder.build(def.request(), ctx, def.timeout());
            HttpReply reply = executor.execute(call, def.proxy());
            Optional<ExpectationCheck.Mismatch> mismatch = expectationCheck.check(reply, def.expect());
            if (mismatch.isEmpty()) {
                return CheckResult.ok(def.key(), at, reply.status(), reply.latencyMs());
            }
            Classification c = classifier.classifyMainReply(reply, mismatch.get());
            return fail(def.key(), at, c, Stage.MAIN, reply.latencyMs());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            Classification c = classifier.classifyException(e, Stage.MAIN, viaProxy(def));
            return fail(def.key(), at, c, Stage.MAIN, 0);
        }
    }

    private CheckResult fail(ProbeKey key, Instant at, Classification c, Stage stage, long latencyMs) {
        return CheckResult.failed(key, at, c.category(), stage, c.httpStatus(), latencyMs, masker.mask(c.detail()));
    }

    private static boolean viaProxy(ProbeDefinition def) {
        return !ProbeCatalog.NONE.equalsIgnoreCase(def.proxy());
    }

    private record Outcome<T>(T value, CheckResult failure) {
        static <T> Outcome<T> ok(T value) {
            return new Outcome<>(value, null);
        }

        static <T> Outcome<T> failed(CheckResult failure) {
            return new Outcome<>(null, failure);
        }
    }
}
