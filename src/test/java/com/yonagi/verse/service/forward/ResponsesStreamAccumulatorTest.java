package com.yonagi.verse.service.forward;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesStreamAccumulatorTest {
    @Test
    void successfulTerminalIsFrozenAndSurvivesLaterMalformedOrConflictingFrames() {
        ResponsesStreamAccumulator accumulator = new ResponsesStreamAccumulator();
        assertNull(accumulator.snapshot().response());
        accumulator.accept("response.created", "{}");
        accumulator.accept("response.completed",
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":5}}}");
        ResponsesStreamAccumulator.Snapshot snapshot = accumulator.snapshot();
        snapshot.response().put("status", "failed");
        accumulator.accept("response.completed", "broken");
        accumulator.accept("response.failed", "{\"response\":{\"status\":\"failed\"}}");
        assertTrue(snapshot.completed());
        assertEquals("SUCCESS", snapshot.status());
        assertEquals("completed", accumulator.snapshot().response().getString("status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"response.failed", "response.incomplete"})
    void completedFrameCannotReuseAnEarlierFailedBody(String terminal) {
        ResponsesStreamAccumulator accumulator = new ResponsesStreamAccumulator();
        accumulator.accept(terminal, "{\"response\":{\"usage\":{\"input_tokens\":5}}}");
        accumulator.accept("response.completed", "{\"type\":\"response.completed\"}");
        assertFalse(accumulator.snapshot().completed());
        assertEquals("response.failed".equals(terminal) ? "FAIL" : "ABORTED", accumulator.snapshot().status());
        assertEquals(5, accumulator.snapshot().response().getJSONObject("usage").getIntValue("input_tokens"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken", "null", "{}", "{\"response\":{}}", "{\"response\":123}",
            "{\"response\":{\"status\":\"failed\"}}", "{\"response\":{\"error\":{\"message\":\"failed\"}}}"})
    void malformedOrFailedCompletedBodyCannotProveSuccessfulExecution(String data) {
        ResponsesStreamAccumulator accumulator = new ResponsesStreamAccumulator();
        accumulator.accept("response.completed", data);
        assertFalse(accumulator.snapshot().completed());
        assertNotEquals("SUCCESS", accumulator.snapshot().status());
    }
}
