package com.uidai.enu.extapipulse.json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPathLiteTest {

    private final JsonNode root;

    JsonPathLiteTest() throws Exception {
        root = new ObjectMapper().readTree("""
                {"status":"OK","code":0,"data":{"items":[{"id":"a1"},{"id":"b2"}],"odd-key":{"v":true}},"none":null}
                """);
    }

    @Test
    void readsFields() {
        assertEquals(Optional.of("OK"), JsonPathLite.readText(root, "$.status"));
        assertEquals(Optional.of("0"), JsonPathLite.readText(root, "$.code"));
        assertEquals(Optional.of("b2"), JsonPathLite.readText(root, "$.data.items[1].id"));
        assertEquals(Optional.of("true"), JsonPathLite.readText(root, "$.data['odd-key'].v"));
    }

    @Test
    void missingIsEmpty() {
        assertTrue(JsonPathLite.readText(root, "$.data.items[5].id").isEmpty());
        assertTrue(JsonPathLite.readText(root, "$.nope.deeper").isEmpty());
        assertTrue(JsonPathLite.readText(root, "$.none.x").isEmpty());
    }

    @Test
    void rejectsBadPaths() {
        assertThrows(IllegalArgumentException.class, () -> JsonPathLite.validate("status"));
        assertThrows(IllegalArgumentException.class, () -> JsonPathLite.validate("$.items[x]"));
        assertThrows(IllegalArgumentException.class, () -> JsonPathLite.validate("$..a"));
    }
}
