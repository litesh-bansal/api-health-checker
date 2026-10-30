package com.uidai.enu.extapipulse.support;

import tools.jackson.databind.ObjectMapper;
import com.uidai.enu.extapipulse.alert.AlertChannel;
import com.uidai.enu.extapipulse.alert.AlertDispatcher;
import com.uidai.enu.extapipulse.alert.AlertFormatter;
import com.uidai.enu.extapipulse.alert.RecipientResolver;
import com.uidai.enu.extapipulse.auth.AuthResolver;
import com.uidai.enu.extapipulse.auth.TokenCache;
import com.uidai.enu.extapipulse.classify.ExpectationCheck;
import com.uidai.enu.extapipulse.classify.FailureClassifier;
import com.uidai.enu.extapipulse.config.ProbeCatalog;
import com.uidai.enu.extapipulse.config.PulseProperties;
import com.uidai.enu.extapipulse.control.ControlCheckService;
import com.uidai.enu.extapipulse.http.CallBuilder;
import com.uidai.enu.extapipulse.http.HttpClientRegistry;
import com.uidai.enu.extapipulse.http.HttpExecutor;
import com.uidai.enu.extapipulse.incident.AlertEvent;
import com.uidai.enu.extapipulse.incident.InMemoryIncidentStore;
import com.uidai.enu.extapipulse.incident.IncidentTracker;
import com.uidai.enu.extapipulse.probe.ProbeRunner;
import com.uidai.enu.extapipulse.template.SecretMasker;
import com.uidai.enu.extapipulse.template.TemplateRenderer;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** The same wiring as PulseConfiguration, without Spring, plus a channel that records what it was asked to send. */
public class TestGraph implements AutoCloseable {

    public record Sent(AlertEvent event, Set<String> recipients, String subject, String body) {}

    public final ProbeCatalog catalog;
    public final SecretMasker masker = new SecretMasker();
    public final InMemoryIncidentStore store = new InMemoryIncidentStore();
    public final IncidentTracker tracker;
    public final ProbeRunner runner;
    public final HttpClientRegistry clients;
    public final List<Sent> sent = new CopyOnWriteArrayList<>();

    public TestGraph(PulseProperties props, Map<String, String> env, Clock clock) {
        catalog = new ProbeCatalog(props, env::get);
        TemplateRenderer renderer = new TemplateRenderer(env::get, masker, clock);
        clients = new HttpClientRegistry(catalog, renderer);
        HttpExecutor executor = new HttpExecutor(clients);
        CallBuilder callBuilder = new CallBuilder(renderer);
        ExpectationCheck expectationCheck = new ExpectationCheck(new ObjectMapper());
        FailureClassifier classifier = new FailureClassifier();
        AuthResolver auth = new AuthResolver(executor, callBuilder, renderer, expectationCheck,
                new TokenCache(clock), clock);
        ControlCheckService control = new ControlCheckService(catalog, executor, classifier, clock);
        tracker = new IncidentTracker(store, catalog::route);
        AlertFormatter formatter = new AlertFormatter(catalog, masker);
        AlertChannel recording = new AlertChannel() {
            @Override
            public String name() {
                return "email";
            }

            @Override
            public void send(AlertEvent event, Set<String> recipients) {
                sent.add(new Sent(event, recipients, formatter.subject(event), formatter.body(event)));
            }
        };
        AlertDispatcher dispatcher = new AlertDispatcher(catalog, new RecipientResolver(catalog), tracker,
                List.of(recording));
        runner = new ProbeRunner(catalog, auth, callBuilder, executor, expectationCheck, classifier, control,
                tracker, dispatcher, masker, clock);
    }

    @Override
    public void close() {
        clients.close();
    }
}
