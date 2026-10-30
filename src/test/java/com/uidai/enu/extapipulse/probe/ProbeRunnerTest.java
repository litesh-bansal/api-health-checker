package com.uidai.enu.extapipulse.probe;

import com.uidai.enu.extapipulse.config.ProbeCatalog;
import com.uidai.enu.extapipulse.config.PulseProperties;
import com.uidai.enu.extapipulse.config.PulseProperties.AuthProfileProps;
import com.uidai.enu.extapipulse.config.PulseProperties.ControlCheckProps;
import com.uidai.enu.extapipulse.config.PulseProperties.DocProps;
import com.uidai.enu.extapipulse.incident.AlertEvent;
import com.uidai.enu.extapipulse.incident.IncidentStatus;
import com.uidai.enu.extapipulse.model.CheckResult;
import com.uidai.enu.extapipulse.model.FailureCategory;
import com.uidai.enu.extapipulse.model.ProbeDefinition;
import com.uidai.enu.extapipulse.model.ProbeKey;
import com.uidai.enu.extapipulse.model.Stage;
import com.uidai.enu.extapipulse.support.FakeApi;
import com.uidai.enu.extapipulse.support.FakeApi.Response;
import com.uidai.enu.extapipulse.support.MutableClock;
import com.uidai.enu.extapipulse.support.TestGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.uidai.enu.extapipulse.support.Fixtures.alerting;
import static com.uidai.enu.extapipulse.support.Fixtures.doc;
import static com.uidai.enu.extapipulse.support.Fixtures.expectJson;
import static com.uidai.enu.extapipulse.support.Fixtures.get;
import static com.uidai.enu.extapipulse.support.Fixtures.post;
import static com.uidai.enu.extapipulse.support.Fixtures.props;
import static com.uidai.enu.extapipulse.support.Fixtures.source;
import static com.uidai.enu.extapipulse.support.Fixtures.tokenCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End to end against a local fake API: real HTTP, real auth flow, real state machine. */
class ProbeRunnerTest {

    private static final Map<String, String> ENV = Map.of("TN_SECRET", "super-secret-value");
    private static final Map<String, String> JSON = Map.of("Content-Type", "application/json");

    private FakeApi api;
    private TestGraph graph;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T05:00:00Z"));

    @BeforeEach
    void setUp() throws Exception {
        api = new FakeApi();
    }

    @AfterEach
    void tearDown() {
        if (graph != null) graph.close();
        api.close();
    }

    // ---------------------------------------------------------------- auth flow

    @Test
    void logsInThenCallsWithTokenAndReusesCachedToken() throws Exception {
        api.on("/oauth/token", 200, "{\"access_token\":\"tok-1\",\"expires_in\":3600}");
        api.on("/verify", r -> "Bearer tok-1".equals(r.header("Authorization"))
                ? Response.json(200, "{\"status\":\"OK\"}") : Response.json(401, "{}"));

        build(doc("DL", "tn-login", post("/verify", JSON, "{\"ref\":\"T1\"}"), expectJson("$.status", "OK")));

        assertTrue(check("DL").success());
        assertTrue(check("DL").success());
        assertEquals(1, api.hits("/oauth/token"), "second check used the cached token");
        assertTrue(api.requests().get(0).body().contains("super-secret-value"), "env secret sent in login body");
    }

    @Test
    void revokedTokenTriggersExactlyOneReLogin() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        api.on("/oauth/token", r -> Response.json(200,
                "{\"access_token\":\"tok-" + logins.incrementAndGet() + "\",\"expires_in\":3600}"));
        AtomicInteger acceptedToken = new AtomicInteger(1);
        api.on("/verify", r -> ("Bearer tok-" + acceptedToken.get()).equals(r.header("Authorization"))
                ? Response.json(200, "{\"status\":\"OK\"}") : Response.json(401, "{}"));
        build(doc("DL", "tn-login", post("/verify", JSON, "{}"), expectJson("$.status", "OK")));

        assertTrue(check("DL").success());
        acceptedToken.set(2); // vendor revokes tok-1 early
        assertTrue(check("DL").success(), "401 with cached token -> re-login -> success");
        assertEquals(2, api.hits("/oauth/token"));
    }

    @Test
    void loginServerErrorIsAuthEndpointDown() throws Exception {
        api.on("/oauth/token", 500, "{\"error\":\"internal\"}");
        build(doc("DL", "tn-login", post("/verify", JSON, "{}"), null));

        CheckResult r = check("DL");
        assertEquals(FailureCategory.AUTH_ENDPOINT_DOWN, r.category());
        assertEquals(Stage.AUTH, r.stage());
        assertEquals(0, api.hits("/verify"), "main call never attempted");
    }

    @Test
    void loginWithoutTokenIsExtractFailed() throws Exception {
        api.on("/oauth/token", 200, "{\"accessToken\":\"renamed-field\"}");
        build(doc("DL", "tn-login", post("/verify", JSON, "{}"), null));

        assertEquals(FailureCategory.AUTH_EXTRACT_FAILED, check("DL").category());
    }

    @Test
    void rejectedLoginIsNotRetried() throws Exception {
        api.on("/oauth/token", 401, "{\"error\":\"invalid_client\"}");
        build(withRetries(doc("DL", "tn-login", post("/verify", JSON, "{}"), null), 2));

        assertEquals(FailureCategory.AUTH_REJECTED, check("DL").category());
        assertEquals(1, api.hits("/oauth/token"), "no retry: repeating a bad login can lock the account");
    }

    @Test
    void sessionIdGoesIntoTheMainBody() throws Exception {
        api.on("/login", 200, "{\"data\":{\"sessionId\":\"sess-42\"}}");
        api.on("/rc", r -> r.body().contains("\"session\":\"sess-42\"")
                ? Response.json(200, "{}") : Response.json(400, "{}"));
        AuthProfileProps session = tokenCall(api.baseUrl() + "/login", "{}", Map.of("sessionId", "$.data.sessionId"), null);
        build(Map.of("ka-session", session),
                doc("RC", "ka-session", post("/rc", JSON, "{\"session\":\"{{auth.sessionId}}\"}"), null));

        assertTrue(check("RC").success());
    }

    @Test
    void noAuthApiNeedsNoLogin() throws Exception {
        api.on("/caste", 200, "{}");
        build(doc("CASTE", "none", get("/caste"), null));

        assertTrue(check("CASTE").success());
        assertEquals(0, api.hits("/oauth/token"));
    }

    // ---------------------------------------------------------------- main call failures

    @Test
    void serverErrorIsRetriedThenReportedAsVendorDown() throws Exception {
        api.on("/verify", 503, "{\"error\":\"maintenance\"}");
        build(withRetries(doc("DL", "none", get("/verify"), null), 2));

        CheckResult r = check("DL");
        assertEquals(FailureCategory.VENDOR_DOWN, r.category());
        assertEquals(503, r.httpStatus());
        assertEquals(3, api.hits("/verify"), "1 attempt + 2 retries");
    }

    @Test
    void wrongBodyIsContract() throws Exception {
        api.on("/verify", 200, "{\"status\":\"ERROR\"}");
        build(doc("DL", "none", get("/verify"), expectJson("$.status", "OK")));

        CheckResult r = check("DL");
        assertEquals(FailureCategory.CONTRACT, r.category());
        assertTrue(r.detail().contains("'ERROR'"), r.detail());
    }

    @Test
    void hangingApiIsVendorDown() throws Exception {
        api.on("/verify", r -> Response.slow(200, "{}", 2_000));
        DocProps d = new DocProps("DL", null, "none", null, null, get("/verify"), null, null, 300, 0, null);
        build(d);

        assertEquals(FailureCategory.VENDOR_DOWN, check("DL").category());
    }

    // ---------------------------------------------------------------- full flow with alerts

    @Test
    void vendorOutageAlertsOnceThenRecovers() throws Exception {
        api.on("/ping", 200, "pong");
        api.on("/verify", 503, "{}");
        build(Map.of(), Map.of("none", control(api.baseUrl() + "/ping")),
                doc("DL", "none", get("/verify"), null));
        ProbeDefinition def = graph.catalog.probe(new ProbeKey("MAHARASHTRA", "DL"));

        for (int i = 0; i < 6; i++) {
            graph.runner.runAndRecord(def);
            clock.advance(Duration.ofMinutes(5));
        }
        assertEquals(1, graph.sent.size(), "one DOWN email for 6 failed checks");
        TestGraph.Sent down = graph.sent.get(0);
        assertEquals(AlertEvent.Type.DOWN, down.event().type());
        assertEquals(Set.of("dev@ours.com", "support@mh.gov.in"), down.recipients());
        assertTrue(down.subject().startsWith("[TEST] [DOWN] MAHARASHTRA / DL - VENDOR_DOWN"), down.subject());
        assertTrue(down.body().contains("Our connectivity:"), down.body());

        api.on("/verify", 200, "{}");
        graph.runner.runAndRecord(def);
        clock.advance(Duration.ofMinutes(5));
        graph.runner.runAndRecord(def);

        assertEquals(2, graph.sent.size());
        TestGraph.Sent rec = graph.sent.get(1);
        assertEquals(AlertEvent.Type.RECOVERED, rec.event().type());
        assertEquals(Set.of("dev@ours.com", "support@mh.gov.in"), rec.recipients(), "everyone who was told it was down");
        assertEquals(IncidentStatus.UP, graph.store.find(def.key()).orElseThrow().snapshot().status());
    }

    @Test
    void ourNetworkDownSuppressesVendorEmailsAndAlertsInfraInstead() throws Exception {
        api.on("/verify", 503, "{}");
        String deadControl = "http://127.0.0.1:1/";   // nothing listens on port 1
        build(Map.of(), Map.of("none", control(deadControl)), doc("DL", "none", get("/verify"), null));
        ProbeDefinition def = graph.catalog.probe(new ProbeKey("MAHARASHTRA", "DL"));
        ProbeCatalog.ControlCheck controlCheck = graph.catalog.controlCheck("none");

        for (int i = 0; i < 5; i++) {
            graph.runner.runAndRecord(def);
            graph.runner.runControlAndRecord(controlCheck);
            clock.advance(Duration.ofMinutes(1));
        }
        assertEquals(1, graph.sent.size(), "only the infra alert, no vendor email");
        TestGraph.Sent sent = graph.sent.get(0);
        assertTrue(sent.event().key().isControl());
        assertEquals(FailureCategory.NETWORK, sent.event().category());
        assertEquals(Set.of("infra@ours.com"), sent.recipients());
        assertTrue(graph.store.find(def.key()).orElseThrow().snapshot().lastFailureBlamedOnOurNetwork());
    }

    @Test
    void secretsNeverReachTheEmail() throws Exception {
        api.on("/oauth/token", 500, "{\"echo\":\"super-secret-value\"}");
        build(withThreshold(doc("DL", "tn-login", post("/verify", JSON, "{}"), null)));
        ProbeDefinition def = graph.catalog.probe(new ProbeKey("MAHARASHTRA", "DL"));

        graph.runner.runAndRecord(def);

        assertEquals(1, graph.sent.size());
        assertFalse(graph.sent.get(0).body().contains("super-secret-value"), graph.sent.get(0).body());
        assertTrue(graph.sent.get(0).body().contains("****"));
    }

    // ---------------------------------------------------------------- helpers

    private CheckResult check(String docId) throws InterruptedException {
        return graph.runner.check(graph.catalog.probe(new ProbeKey("MAHARASHTRA", docId)));
    }

    private void build(DocProps... docs) {
        build(Map.of(), Map.of(), docs);
    }

    private void build(Map<String, AuthProfileProps> extraAuth, DocProps... docs) {
        build(extraAuth, Map.of(), docs);
    }

    private void build(Map<String, AuthProfileProps> extraAuth, Map<String, ControlCheckProps> control,
                       DocProps... docs) {
        Map<String, AuthProfileProps> auth = new java.util.HashMap<>(extraAuth);
        auth.put("tn-login", tokenCall(api.baseUrl() + "/oauth/token",
                "{\"clientSecret\":\"{{env.TN_SECRET}}\"}",
                Map.of("token", "$.access_token", "expiresIn", "$.expires_in"),
                Map.of("Authorization", "Bearer {{auth.token}}")));
        PulseProperties p = props(List.of(source("MAHARASHTRA", api.baseUrl(), null, List.of("support@mh.gov.in"), docs)),
                auth, control, null, alerting(3, 2, 60, 1.0, 60, 0));
        graph = new TestGraph(p, ENV, clock);
    }

    private static ControlCheckProps control(String url) {
        return new ControlCheckProps(List.of(url), 1000, 0, 60);
    }

    private static DocProps withRetries(DocProps d, int retries) {
        return new DocProps(d.docId(), d.enabled(), d.auth(), d.proxy(), d.contacts(), d.request(), d.expect(),
                d.intervalSec(), d.timeoutMs(), retries, d.alerting());
    }

    private static DocProps withThreshold(DocProps d) {
        return new DocProps(d.docId(), d.enabled(), d.auth(), d.proxy(), d.contacts(), d.request(), d.expect(),
                d.intervalSec(), d.timeoutMs(), d.retries(), alerting(1, 1, 60, 1.0, 60, 0));
    }
}
