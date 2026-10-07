package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class UpstreamResponseBodyTest {
    @Test void readingFailureAfterSuccessRetainsUnknownExecutionAndOriginalCause() {
        var cause = new IOException("response disconnected");
        InputStream body = new InputStream() {
            @Override public int read() throws IOException { throw cause; }
        };
        var failure = assertThrows(UpstreamFailureException.class, () -> UpstreamResponseBody.read(body, 32));
        assertEquals(UpstreamExecutionOutcome.UNKNOWN, failure.getExecutionOutcome());
        assertEquals(LlmForwardErrorCodeEnum.FORWARD_FAILED.code(), failure.getErrorCode());
        assertSame(cause, failure.getCause());
        assertFalse(failure.isRetryable());
    }

    @Test void exactLimitJsonStillReturnsSuccessfulBody() {
        byte[] bytes = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
        assertEquals("{\"data\":[]}", UpstreamResponseBody.readJson(new ByteArrayInputStream(bytes), bytes.length));
    }
}
