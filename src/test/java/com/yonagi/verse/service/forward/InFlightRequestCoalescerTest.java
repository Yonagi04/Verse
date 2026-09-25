package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.enums.ModelOperation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InFlightRequestCoalescerTest {

    private final InFlightRequestCoalescer coalescer = new InFlightRequestCoalescer();
    private final UserContext context = new UserContext()
            .setUserId(1L)
            .setCurrentTenantId(2L)
            .setApiKeyId(3L);

    @Test
    void identicalConcurrentRequestsShareOneExecutionAndRequestId() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<InFlightRequestCoalescer.CoalescedResponse> first = executor.submit(() -> coalescer.execute(
                    context, "{\"model\":\"alias\"}", "request-1", () -> {
                        executions.incrementAndGet();
                        actionStarted.countDown();
                        await(releaseAction);
                        return "response";
                    }
            ));
            assertTrue(actionStarted.await(1, TimeUnit.SECONDS));

            CountDownLatch duplicateStarted = new CountDownLatch(1);
            Future<InFlightRequestCoalescer.CoalescedResponse> duplicate = executor.submit(() -> {
                duplicateStarted.countDown();
                return coalescer.execute(context, "{\"model\":\"alias\"}", "request-2", () -> {
                    executions.incrementAndGet();
                    return "duplicate-response";
                });
            });
            assertTrue(duplicateStarted.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> duplicate.get(100, TimeUnit.MILLISECONDS));
            releaseAction.countDown();

            assertEquals("response", first.get(1, TimeUnit.SECONDS).body());
            InFlightRequestCoalescer.CoalescedResponse duplicateResponse = duplicate.get(1, TimeUnit.SECONDS);
            assertEquals("response", duplicateResponse.body());
            assertEquals("request-1", duplicateResponse.requestId());
            assertEquals(1, executions.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void completedRequestDoesNotCacheItsResponse() {
        AtomicInteger executions = new AtomicInteger();

        coalescer.execute(context, "same-body", "request-1", () -> String.valueOf(executions.incrementAndGet()));
        InFlightRequestCoalescer.CoalescedResponse second = coalescer.execute(
                context, "same-body", "request-2", () -> String.valueOf(executions.incrementAndGet()));

        assertEquals("2", second.body());
        assertEquals("request-2", second.requestId());
        assertEquals(2, executions.get());
    }

    @Test
    void operationAndContentTypeSeparateOtherwiseIdenticalRequests() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<InFlightRequestCoalescer.CoalescedResponse> first = executor.submit(() -> coalescer.execute(
                    context, ModelOperation.CHAT_COMPLETIONS, "application/json", "same-body", "chat", () -> {
                        started.countDown();
                        await(release);
                        return "chat-result";
                    }));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            var second = coalescer.execute(context, ModelOperation.EMBEDDINGS,
                    "application/json", "same-body", "embedding", () -> "embedding-result");
            var third = coalescer.execute(context, ModelOperation.CHAT_COMPLETIONS,
                    "application/vnd.api+json", "same-body", "other-type", () -> "other-result");
            assertEquals("embedding-result", second.body());
            assertEquals("other-result", third.body());
            release.countDown();
            assertEquals("chat-result", first.get(1, TimeUnit.SECONDS).body());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void multipartAndBinaryOperationsNeverCoalesce() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<InFlightRequestCoalescer.CoalescedResponse> first = executor.submit(() -> coalescer.execute(
                    context, ModelOperation.TRANSCRIPTION, "multipart/form-data", "same", "first", () -> {
                        started.countDown();
                        await(release);
                        return "first-result";
                    }));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            var second = coalescer.execute(context, ModelOperation.TRANSCRIPTION,
                    "multipart/form-data", "same", "second", () -> "second-result");
            assertEquals("second-result", second.body());
            release.countDown();
            assertEquals("first-result", first.get(1, TimeUnit.SECONDS).body());
        } finally {
            executor.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试线程被中断", e);
        }
    }
}
