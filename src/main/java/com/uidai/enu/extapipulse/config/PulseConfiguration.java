package com.uidai.enu.extapipulse.config;

import tools.jackson.databind.ObjectMapper;
import com.uidai.enu.extapipulse.alert.AlertChannel;
import com.uidai.enu.extapipulse.alert.AlertDispatcher;
import com.uidai.enu.extapipulse.alert.AlertFormatter;
import com.uidai.enu.extapipulse.alert.EmailChannel;
import com.uidai.enu.extapipulse.alert.RecipientResolver;
import com.uidai.enu.extapipulse.auth.AuthResolver;
import com.uidai.enu.extapipulse.auth.TokenCache;
import com.uidai.enu.extapipulse.classify.ExpectationCheck;
import com.uidai.enu.extapipulse.classify.FailureClassifier;
import com.uidai.enu.extapipulse.control.ControlCheckService;
import com.uidai.enu.extapipulse.http.CallBuilder;
import com.uidai.enu.extapipulse.http.HttpClientRegistry;
import com.uidai.enu.extapipulse.http.HttpExecutor;
import com.uidai.enu.extapipulse.incident.InMemoryIncidentStore;
import com.uidai.enu.extapipulse.incident.IncidentStore;
import com.uidai.enu.extapipulse.incident.IncidentTracker;
import com.uidai.enu.extapipulse.probe.ProbeRunner;
import com.uidai.enu.extapipulse.probe.ProbeScheduler;
import com.uidai.enu.extapipulse.template.SecretMasker;
import com.uidai.enu.extapipulse.template.TemplateRenderer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Clock;
import java.util.List;

/** Wires the plain-Java classes together. Most classes are framework-free so they are easy to unit test. */
@Configuration
public class PulseConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ProbeCatalog probeCatalog(PulseProperties props, Environment env) {
        return new ProbeCatalog(props, env::getProperty);
    }

    @Bean
    public SecretMasker secretMasker() {
        return new SecretMasker();
    }

    @Bean
    public TemplateRenderer templateRenderer(Environment env, SecretMasker masker, Clock clock) {
        return new TemplateRenderer(env::getProperty, masker, clock);
    }

    @Bean(destroyMethod = "close")
    public HttpClientRegistry httpClientRegistry(ProbeCatalog catalog, TemplateRenderer renderer) {
        return new HttpClientRegistry(catalog, renderer);
    }

    @Bean
    public HttpExecutor httpExecutor(HttpClientRegistry clients) {
        return new HttpExecutor(clients);
    }

    @Bean
    public CallBuilder callBuilder(TemplateRenderer renderer) {
        return new CallBuilder(renderer);
    }

    @Bean
    public ExpectationCheck expectationCheck(ObjectMapper mapper) {
        return new ExpectationCheck(mapper);
    }

    @Bean
    public FailureClassifier failureClassifier() {
        return new FailureClassifier();
    }

    @Bean
    public TokenCache tokenCache(Clock clock) {
        return new TokenCache(clock);
    }

    @Bean
    public AuthResolver authResolver(HttpExecutor executor, CallBuilder callBuilder, TemplateRenderer renderer,
                                     ExpectationCheck expectationCheck, TokenCache cache, Clock clock) {
        return new AuthResolver(executor, callBuilder, renderer, expectationCheck, cache, clock);
    }

    @Bean
    public ControlCheckService controlCheckService(ProbeCatalog catalog, HttpExecutor executor,
                                                   FailureClassifier classifier, Clock clock) {
        return new ControlCheckService(catalog, executor, classifier, clock);
    }

    /** Replace with a DB/Redis-backed store to survive restarts and run more than one instance. */
    @Bean
    public IncidentStore incidentStore() {
        return new InMemoryIncidentStore();
    }

    @Bean
    public IncidentTracker incidentTracker(IncidentStore store, ProbeCatalog catalog) {
        return new IncidentTracker(store, catalog::route);
    }

    @Bean
    public RecipientResolver recipientResolver(ProbeCatalog catalog) {
        return new RecipientResolver(catalog);
    }

    @Bean
    public AlertFormatter alertFormatter(ProbeCatalog catalog, SecretMasker masker) {
        return new AlertFormatter(catalog, masker);
    }

    @Bean
    public EmailChannel emailChannel(ObjectProvider<JavaMailSender> mailSender, AlertFormatter formatter,
                                     ProbeCatalog catalog) {
        return new EmailChannel(mailSender.getIfAvailable(), formatter, catalog.mail());
    }

    // Jira later: add a @Bean JiraChannel implementing AlertChannel with name() = "jira",
    // then add "jira" to `channels` for the categories that should raise tickets.

    @Bean
    public AlertDispatcher alertDispatcher(ProbeCatalog catalog, RecipientResolver recipients,
                                           IncidentTracker tracker, List<AlertChannel> channels) {
        return new AlertDispatcher(catalog, recipients, tracker, channels);
    }

    @Bean
    public ProbeRunner probeRunner(ProbeCatalog catalog, AuthResolver auth, CallBuilder callBuilder,
                                   HttpExecutor executor, ExpectationCheck expectationCheck,
                                   FailureClassifier classifier, ControlCheckService control,
                                   IncidentTracker tracker, AlertDispatcher dispatcher, SecretMasker masker,
                                   Clock clock) {
        return new ProbeRunner(catalog, auth, callBuilder, executor, expectationCheck, classifier, control,
                tracker, dispatcher, masker, clock);
    }

    @Bean(destroyMethod = "stop")
    public ProbeScheduler probeScheduler(ProbeCatalog catalog, ProbeRunner runner, PulseProperties props) {
        return new ProbeScheduler(catalog, runner, !Boolean.FALSE.equals(props.enabled()));
    }
}
