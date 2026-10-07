package com.yonagi.verse.resilience.impl;

import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.service.forward.UpstreamFailureException;
import com.yonagi.verse.service.forward.UpstreamExecutionOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class Resilience4jTimeLimiterTest {
    private ExecutorService pool(Resilience4jTimeLimiter limiter) {
        return (ExecutorService) ReflectionTestUtils.getField(limiter, "workerPool");
    }

    @Test void preservesOriginalNonRetryableFailure() {
        var limiter = new Resilience4jTimeLimiter(1000);
        try {
            var failure = new UpstreamFailureException("invalid input", LlmForwardErrorCodeEnum.UPSTREAM_ERROR, false);
            assertSame(failure, assertThrows(UpstreamFailureException.class,
                    () -> limiter.execute(() -> { throw failure; })));
        } finally { pool(limiter).shutdownNow(); }
    }

    @Test void defaultExecutionCapacityIsBounded() {
        var limiter = new Resilience4jTimeLimiter(1000);
        try { assertTrue(((ThreadPoolExecutor) pool(limiter)).getMaximumPoolSize() <= 32); }
        finally { pool(limiter).shutdownNow(); }
    }

    @Test void springContextClosesOwnedExecutor() {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(Resilience4jTimeLimiter.class, () -> new Resilience4jTimeLimiter(1000));
        context.refresh();
        var limiter = context.getBean(Resilience4jTimeLimiter.class);
        try {
            context.close();
            assertTrue(pool(limiter).isShutdown());
        } finally { pool(limiter).shutdownNow(); }
    }

    @Test void timedOutTaskRetainsCapacityUntilItActuallyExits() throws Exception {
        var limiter = new Resilience4jTimeLimiter(100, 1, 0);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var callers = Executors.newSingleThreadExecutor();
        try {
            var result = callers.submit(() -> assertThrows(UpstreamFailureException.class, () -> limiter.execute(() -> {
                started.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { /* 模拟不响应取消的 I/O。 */ }
                }
                return "late";
            })));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var timeout = result.get(2, TimeUnit.SECONDS);
            assertEquals(UpstreamExecutionOutcome.UNKNOWN, timeout.getExecutionOutcome());
            assertFalse(timeout.isRetryable());
            var secondStarted = new AtomicBoolean();
            var rejected = assertThrows(UpstreamFailureException.class,
                    () -> limiter.execute(() -> { secondStarted.set(true); return "duplicate"; }));
            assertEquals(LlmForwardErrorCodeEnum.UPSTREAM_CAPACITY_EXCEEDED.code(), rejected.getErrorCode());
            assertEquals(UpstreamExecutionOutcome.NOT_SENT, rejected.getExecutionOutcome());
            assertFalse(rejected.isUpstreamHealthFailure());
            assertFalse(secondStarted.get());
        } finally {
            release.countDown(); callers.shutdownNow(); limiter.close();
            assertTrue(pool(limiter).awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test void callerInterruptionCancelsWorkerAndPreservesInterruptFlag() throws Exception {
        var limiter = new Resilience4jTimeLimiter(10000, 1, 0);
        var started = new CountDownLatch(1);
        var workerStopped = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var outcome = new java.util.concurrent.atomic.AtomicReference<UpstreamExecutionOutcome>();
        var caller = new Thread(() -> {
            var failure = assertThrows(UpstreamFailureException.class, () -> limiter.execute(() -> {
                started.countDown();
                try { new CountDownLatch(1).await(); return "never"; }
                finally { workerStopped.countDown(); }
            }));
            interrupted.set(Thread.currentThread().isInterrupted());
            assertFalse(failure.isUpstreamHealthFailure());
            outcome.set(failure.getExecutionOutcome());
        });
        try {
            caller.start(); assertTrue(started.await(2, TimeUnit.SECONDS)); caller.interrupt(); caller.join(2000);
            assertFalse(caller.isAlive()); assertTrue(workerStopped.await(2, TimeUnit.SECONDS));
            assertTrue(interrupted.get()); assertEquals(UpstreamExecutionOutcome.UNKNOWN, outcome.get());
        } finally { caller.interrupt(); limiter.close(); }
    }

    @Test void closedPoolRejectsWithoutExecutingAndConfigurationIsValidated() {
        var limiter = new Resilience4jTimeLimiter(1000, 1, 0);
        limiter.close();
        var failure = assertThrows(UpstreamFailureException.class, () -> limiter.execute(() -> fail("must not execute")));
        assertEquals(UpstreamExecutionOutcome.NOT_SENT, failure.getExecutionOutcome());
        assertFalse(failure.isRetryable());
        assertThrows(IllegalArgumentException.class, () -> new Resilience4jTimeLimiter(0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Resilience4jTimeLimiter(100, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Resilience4jTimeLimiter(100, 1, -1));
    }

    @Test void workerInterruptionIsNotReportedAsCallerInterruptionOrUpstreamFailure() {
        var limiter = new Resilience4jTimeLimiter(1000, 1, 0);
        try {
            var failure = assertThrows(UpstreamFailureException.class,
                    () -> limiter.execute(() -> { throw new InterruptedException("worker cancelled"); }));
            assertFalse(Thread.currentThread().isInterrupted());
            assertFalse(failure.isUpstreamHealthFailure());
            assertEquals(UpstreamExecutionOutcome.UNKNOWN, failure.getExecutionOutcome());
        } finally { Thread.interrupted(); limiter.close(); }
    }
}
