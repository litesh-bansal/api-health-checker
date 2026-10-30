package com.uidai.enu.extapipulse.classify;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.uidai.enu.extapipulse.http.HttpReply;
import com.uidai.enu.extapipulse.json.JsonPathLite;
import com.uidai.enu.extapipulse.model.Expectation;

import java.util.Objects;
import java.util.Optional;

/** Compares a response with what a healthy response should look like. */
public class ExpectationCheck {

    public enum Kind { STATUS, BODY, LATENCY }

    public record Mismatch(Kind kind, String reason) {}

    private final ObjectMapper mapper;

    public ExpectationCheck(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Empty if the response is healthy. Checks run in order: status, body, latency. */
    public Optional<Mismatch> check(HttpReply reply, Expectation expect) {
        if (!expect.statuses().contains(reply.status())) {
            return Optional.of(new Mismatch(Kind.STATUS,
                    "HTTP " + reply.status() + " (expected " + expect.statuses() + ")"));
        }
        if (expect.bodyContains() != null
                && (reply.body() == null || !reply.body().contains(expect.bodyContains()))) {
            return Optional.of(new Mismatch(Kind.BODY, "Body does not contain '" + expect.bodyContains() + "'"));
        }
        if (expect.jsonPath() != null) {
            Optional<Mismatch> m = checkJson(reply, expect);
            if (m.isPresent()) {
                return m;
            }
        }
        if (expect.maxLatencyMs() != null && reply.latencyMs() > expect.maxLatencyMs()) {
            return Optional.of(new Mismatch(Kind.LATENCY,
                    "Took " + reply.latencyMs() + " ms (limit " + expect.maxLatencyMs() + " ms)"));
        }
        return Optional.empty();
    }

    private Optional<Mismatch> checkJson(HttpReply reply, Expectation expect) {
        JsonNode root;
        try {
            root = parse(reply.body());
        } catch (Exception e) {
            return Optional.of(new Mismatch(Kind.BODY, "Response is not valid JSON"));
        }
        Optional<String> actual = JsonPathLite.readText(root, expect.jsonPath());
        if (expect.equalsValue() != null) {
            if (actual.isEmpty()) {
                return Optional.of(new Mismatch(Kind.BODY, expect.jsonPath() + " is missing"));
            }
            if (!Objects.equals(actual.get(), expect.equalsValue())) {
                return Optional.of(new Mismatch(Kind.BODY,
                        expect.jsonPath() + " is '" + actual.get() + "' (expected '" + expect.equalsValue() + "')"));
            }
        } else if (actual.isEmpty()) {
            // jsonPath given without equals: the field must at least exist.
            return Optional.of(new Mismatch(Kind.BODY, expect.jsonPath() + " is missing"));
        }
        return Optional.empty();
    }

    public JsonNode parse(String body) throws Exception {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("empty body");
        }
        return mapper.readTree(body);
    }
}
