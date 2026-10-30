package com.uidai.enu.extapipulse.template;

import com.uidai.enu.extapipulse.template.TemplateRenderer.Escape;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateRendererTest {

    private final SecretMasker masker = new SecretMasker();
    private final TemplateRenderer renderer = new TemplateRenderer(
            Map.of("CLIENT_SECRET", "s3cr\"et!", "USER", "svc user")::get, masker,
            Clock.fixed(Instant.parse("2026-10-30T05:00:00Z"), ZoneOffset.UTC));

    @Test
    void envAndAuthValuesAreFilledIn() {
        String out = renderer.render("{\"session\":\"{{auth.sessionId}}\",\"secret\":\"{{ env.CLIENT_SECRET }}\"}",
                Map.of("sessionId", "abc123"), Escape.JSON);
        assertEquals("{\"session\":\"abc123\",\"secret\":\"s3cr\\\"et!\"}", out, "quote inside the secret is JSON-escaped");
    }

    @Test
    void formBodiesAreUrlEncoded() {
        assertEquals("user=svc+user", renderer.render("user={{env.USER}}", Map.of(), Escape.URL));
    }

    @Test
    void timeAndUuidPlaceholders() {
        assertEquals("1793336400000", renderer.render("{{now.epochMillis}}", Map.of(), Escape.NONE));
        assertEquals(36, renderer.render("{{uuid}}", Map.of(), Escape.NONE).length());
    }

    @Test
    void missingValuesFailClearly() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> renderer.render("{{env.NOPE}}", Map.of(), Escape.NONE));
        assertTrue(e.getMessage().contains("NOPE"));
        assertThrows(ConfigException.class, () -> renderer.render("{{auth.token}}", Map.of(), Escape.NONE));
        assertThrows(ConfigException.class, () -> renderer.render("{{weird.x}}", Map.of(), Escape.NONE));
    }

    @Test
    void secretsAreMaskedAfterUse() {
        renderer.render("{{env.CLIENT_SECRET}} {{auth.token}}", Map.of("token", "tok-9876"), Escape.NONE);
        String masked = masker.mask("login failed for s3cr\"et! with tok-9876");
        assertFalse(masked.contains("s3cr"));
        assertFalse(masked.contains("tok-9876"));
    }

    @Test
    void findsReferencedKeysForValidation() {
        assertEquals(Set.of("A", "B"), TemplateRenderer.referencedKeys("{{env.A}}-{{auth.x}}-{{env.B}}", "env"));
    }

    @Test
    void contentTypeDecidesEscaping() {
        assertEquals(Escape.JSON, TemplateRenderer.escapeForContentType(Map.of("content-type", "application/json; charset=utf-8")));
        assertEquals(Escape.URL, TemplateRenderer.escapeForContentType(Map.of("Content-Type", "application/x-www-form-urlencoded")));
        assertEquals(Escape.NONE, TemplateRenderer.escapeForContentType(Map.of()));
    }
}
