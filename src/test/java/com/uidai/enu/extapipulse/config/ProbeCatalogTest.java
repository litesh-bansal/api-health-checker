package com.uidai.enu.extapipulse.config;

import com.uidai.enu.extapipulse.config.PulseProperties.DocProps;
import com.uidai.enu.extapipulse.config.PulseProperties.SourceProps;
import com.uidai.enu.extapipulse.model.FailureCategory;
import com.uidai.enu.extapipulse.model.ProbeDefinition;
import com.uidai.enu.extapipulse.model.ProbeKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.uidai.enu.extapipulse.support.Fixtures.alerting;
import static com.uidai.enu.extapipulse.support.Fixtures.apiKey;
import static com.uidai.enu.extapipulse.support.Fixtures.doc;
import static com.uidai.enu.extapipulse.support.Fixtures.expectStatus;
import static com.uidai.enu.extapipulse.support.Fixtures.get;
import static com.uidai.enu.extapipulse.support.Fixtures.post;
import static com.uidai.enu.extapipulse.support.Fixtures.props;
import static com.uidai.enu.extapipulse.support.Fixtures.source;
import static com.uidai.enu.extapipulse.support.Fixtures.tokenCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeCatalogTest {

    private static final Map<String, String> ENV = Map.of("MH_KEY", "k-123", "TN_ID", "id", "TN_SECRET", "sec");

    @Test
    void resolvesInheritanceAndOverrides() {
        DocProps dl = new DocProps("DL", null, null, null, List.of("dl-owner@mh.gov.in"),
                post("/v1/dl", Map.of("Content-Type", "application/json"), "{}"), expectStatus(200, 404),
                120, null, null, alerting(5, 2, 30, 1.0, 30, 0));
        SourceProps mh = new SourceProps("MAHARASHTRA", null, "https://api.mh.gov.in/", "mh-key", null,
                List.of("support@mh.gov.in"), Map.of("Accept", "application/json"), null, null,
                List.of(dl, doc("RC", "none", get("v1/rc"), null)));

        ProbeCatalog catalog = new ProbeCatalog(props(List.of(mh), Map.of("mh-key", apiKey(null, "{{env.MH_KEY}}")),
                null, null, alerting(3, 2, 60, 2.0, 480, 0)), ENV::get);

        ProbeDefinition d = catalog.probe(new ProbeKey("MAHARASHTRA", "DL"));
        assertEquals("https://api.mh.gov.in/v1/dl", d.request().url());
        assertEquals("application/json", d.request().headers().get("Accept"), "source header inherited");
        assertEquals("mh-key", d.authProfile());
        assertEquals(List.of("dl-owner@mh.gov.in"), d.contacts(), "docId contacts replace source contacts");
        assertEquals(Duration.ofSeconds(120), d.interval(), "docId override");
        assertEquals(5, d.alerting().failureThreshold(), "docId alerting override");
        assertEquals(Duration.ofMinutes(30), d.alerting().reminderInterval());
        assertTrue(d.expect().statuses().contains(404));

        ProbeDefinition rc = catalog.probe(new ProbeKey("MAHARASHTRA", "RC"));
        assertEquals("https://api.mh.gov.in/v1/rc", rc.request().url(), "slashes joined correctly");
        assertEquals("none", rc.authProfile(), "auth: none overrides the source's auth");
        assertEquals(List.of("support@mh.gov.in"), rc.contacts());
        assertEquals(Duration.ofSeconds(60), rc.interval(), "global default");
        assertEquals(3, rc.alerting().failureThreshold(), "global alerting");
        assertEquals("X-API-KEY", catalog.authProfile("mh-key").header(), "default api key header");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        SourceProps mh = source("MAHARASHTRA", "https://api.mh.gov.in", "missing-profile", List.of("not-an-email"),
                doc("DL", get("/a"), null),
                doc("DL", get("/b"), null),
                doc("RC", "tn-login", post("/rc", Map.of(), "{\"s\":\"{{auth.sessionId}}\"}"), null));
        Map<String, PulseProperties.AuthProfileProps> auth = Map.of(
                "tn-login", tokenCall("https://login.tn.gov.in/token", "{\"id\":\"{{env.TN_ID}}\",\"x\":\"{{env.NOT_SET}}\"}",
                        Map.of("token", "$.access_token"), Map.of("Authorization", "Bearer {{auth.tokn}}")));

        PulseProperties p = props(List.of(mh), auth, null, null, null);
        Map<FailureCategory, PulseProperties.RouteProps> routing = new HashMap<>(p.routing());
        routing.remove(FailureCategory.SLOW_RESPONSE);
        p = new PulseProperties(p.enabled(), p.defaults(), p.alerting(), p.mail(), p.proxies(), p.controlChecks(),
                p.authProfiles(), p.recipientGroups(), routing, p.sources());

        PulseProperties finalP = p;
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ProbeCatalog(finalP, ENV::get));
        String msg = e.getMessage();
        assertTrue(msg.contains("routing.SLOW_RESPONSE: missing"), msg);
        assertTrue(msg.contains("unknown auth profile 'missing-profile'"), msg);
        assertTrue(msg.contains("duplicate (source, docId)"), msg);
        assertTrue(msg.contains("not a valid email address: not-an-email"), msg);
        assertTrue(msg.contains("{{env.NOT_SET}} is not set"), msg);
        assertTrue(msg.contains("uses {{auth.tokn}} but extract has no 'tokn'"), msg);
        assertTrue(msg.contains("uses {{auth.sessionId}} but auth profile 'tn-login' does not extract it"), msg);
    }

    @Test
    void controlCheckMustReferToAKnownProxy() {
        SourceProps s = source("KA", "https://ka.gov.in", null, List.of(), doc("RC", get("/x"), null));
        PulseProperties p = props(List.of(s), null,
                Map.of("ghost-proxy", new PulseProperties.ControlCheckProps(List.of("https://www.google.com"), null, null, null)),
                null, null);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ProbeCatalog(p, ENV::get));
        assertTrue(e.getMessage().contains("control-checks.ghost-proxy"), e.getMessage());
    }
}
