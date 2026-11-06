package com.yonagi.verse.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class FatalErrorsTest {
    @Test
    void cyclicCauseChainTerminatesWithoutReclassifyingOrdinaryErrors() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> FatalErrors.rethrowIfFatal(first));
    }

    @Test
    void directFallbackAlsoPreservesFatalErrorInstance() {
        OutOfMemoryError fatal = new OutOfMemoryError("fixture fatal");
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> handler.throwableException(
                new MockHttpServletRequest(), new IllegalStateException("wrapped", fatal))));
    }
}
