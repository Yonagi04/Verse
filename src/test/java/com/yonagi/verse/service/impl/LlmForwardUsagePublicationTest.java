package com.yonagi.verse.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.async.api.TokenUsageEventPublisher;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.async.event.LlmAuditEvent;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.PricePeriodType;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.resilience.api.CircuitBreaker;
import com.yonagi.verse.resilience.api.FallbackExecutor;
import com.yonagi.verse.resilience.api.RateLimiter;
import com.yonagi.verse.resilience.impl.Resilience4jTimeLimiter;
import com.yonagi.verse.service.forward.ModelResolver;
import com.yonagi.verse.service.forward.ChatMessage;
import com.yonagi.verse.service.forward.ForwardContext;
import com.yonagi.verse.service.forward.ProviderAdapter;
import com.yonagi.verse.service.forward.UpstreamFailureException;
import com.yonagi.verse.service.forward.UpstreamExecutionOutcome;
import com.yonagi.verse.service.forward.UpstreamErrors;
import com.yonagi.verse.service.forward.OpenAiCompatibleAdapter;
import com.yonagi.verse.service.forward.OpenAiJsonOperationAdapter;
import com.yonagi.verse.service.forward.OpenAiMediaAdapter;
import com.yonagi.verse.service.forward.AdapterExchange;
import com.yonagi.verse.service.forward.MediaOperationAdapter;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import com.yonagi.verse.service.pricing.CostCalculator;
import com.yonagi.verse.service.pricing.PricingResolver;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import com.yonagi.verse.service.usage.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmForwardUsagePublicationTest {
    @ParameterizedTest @ValueSource(strings = {"partial-usage", "no-usage", "empty", "finish-reason-only"})
    void chatEofWithoutDoneFailsWithUnknownExecutionAndRetainsUsage(String payload) {
        tokenPricing();
        String choice = payload.equals("finish-reason-only") ? "{\"finish_reason\":\"stop\"}" : "{\"delta\":{\"content\":\"partial\"}}";
        String usage = "{\"choices\":[" + choice + "],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}";
        boolean hasUsage = payload.equals("partial-usage") || payload.equals("finish-reason-only");
        when(providerAdapter.stream(any())).thenReturn(payload.equals("empty") ? Flux.empty()
                : Flux.just(ServerSentEvent.builder(hasUsage ? usage : "{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}").build()));
        var failure = assertThrows(UpstreamFailureException.class,
                () -> stream(ModelOperation.CHAT_COMPLETIONS, "eof-" + payload).blockLast());
        assertEquals(UpstreamExecutionOutcome.UNKNOWN, failure.getExecutionOutcome());
        assertFalse(failure.isRetryable());
        var event = terminalEvent();
        assertEquals("FAIL", event.getStatus());
        assertEquals(UpstreamExecutionOutcome.UNKNOWN, event.getExecutionOutcome());
        assertEquals(hasUsage ? "ESTIMATED" : "UNKNOWN", event.getUsageSource());
        assertEquals(hasUsage ? CostStatus.CALCULATED : CostStatus.UNCALCULABLE, event.getCostResult().status());
        if (hasUsage) assertEquals(7, event.getTotalTokens());
        else assertNull(event.getTotalTokens());
        verifyNoInteractions(fallbackExecutor);
    }

    @ParameterizedTest @ValueSource(strings = {"compatible", "anthropic"})
    void realHttpChatEofWithoutNativeTerminalCannotBecomeExact(String provider) throws Exception {
        tokenPricing();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        String body = "{\"model\":\"alias\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}";
        String reply = provider.equals("anthropic")
                ? "event: message_start\ndata: {\"message\":{\"usage\":{\"input_tokens\":5}}}\n\n"
                + "event: message_delta\ndata: {\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n"
                : "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}\n\n";
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            primary.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
            ProviderAdapter adapter;
            if (provider.equals("anthropic")) {
                adapter = new com.yonagi.verse.service.forward.NativeChatAdapters.Anthropic();
                primary.setProvider("anthropic");
                when(modelResolver.protocolFor(primary, ModelOperation.CHAT_COMPLETIONS)).thenReturn(UpstreamProtocol.ANTHROPIC_MESSAGES);
            } else adapter = new OpenAiCompatibleAdapter(5000, 5000);
            ReflectionTestUtils.setField(service, "providerAdapter", adapter);
            var received = new java.util.ArrayList<ServerSentEvent<String>>();
            assertThrows(UpstreamFailureException.class, () -> service.chatCompletionStream(context, body,
                    "http-eof", Instant.now()).doOnNext(received::add).blockLast(java.time.Duration.ofSeconds(5)));
            assertEquals(1, calls.get());
            assertFalse(received.stream().anyMatch(e -> "[DONE]".equals(e.data())));
            var event = terminalEvent();
            assertEquals("FAIL", event.getStatus());
            assertEquals(UpstreamExecutionOutcome.UNKNOWN, event.getExecutionOutcome());
            assertEquals("ESTIMATED", event.getUsageSource());
            assertEquals(7, event.getTotalTokens());
            assertEquals(CostStatus.CALCULATED, event.getCostResult().status());
        } finally { server.stop(0); }
    }

    @ParameterizedTest @ValueSource(strings = {"SPEECH", "TRANSCRIPTION"})
    void mediaValidationBeforeSendingStillSettlesAsNotChargeable(String operation) {
        ModelOperation op = ModelOperation.valueOf(operation);
        var adapter = new OpenAiMediaAdapter(op, UpstreamProtocol.OPENAI_COMPAT);
        ReflectionTestUtils.setField(service, "providerAdapter", adapter);
        AdapterExchange.Request request = op == ModelOperation.SPEECH
                ? new AdapterExchange.JsonRequest(op, JSON.parseObject("{\"input\":\"\",\"voice\":\"alloy\"}"))
                : new AdapterExchange.MultipartRequest(op, Map.of(), "clip.wav",
                org.springframework.http.MediaType.parseMediaType("audio/wav"), new byte[0]);
        assertThrows(ClientException.class, () -> service.media(context, op, "alias", request, "invalid-media", Instant.now()));
        var event = terminalEvent();
        assertEquals(CostStatus.NOT_CHARGEABLE, event.getCostResult().status());
        assertEquals("NOT_SENT", JSON.parseObject(JSON.toJSONString(event)).getString("executionOutcome"));
        verify(circuitBreaker, never()).recordFailure(anyString());
    }

    @ParameterizedTest @ValueSource(strings = {"CHAT_COMPLETIONS", "RESPONSES"})
    void cancellationRetainsTerminalUsageAlreadyReceivedBeforeAsyncDelivery(String operation) throws Exception {
        tokenPricing();
        ModelOperation op = ModelOperation.valueOf(operation);
        var upstream = reactor.core.publisher.Sinks.many().unicast().<ServerSentEvent<String>>onBackpressureBuffer();
        when(providerAdapter.stream(any())).thenReturn(upstream.asFlux());
        var delivering = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var subscription = stream(op, "buffered-cancel").subscribe(event -> {
            delivering.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            // publishOn 在取消时允许中断正在等待的交付任务。
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        });
        try {
            assertEquals(reactor.core.publisher.Sinks.EmitResult.OK,
                    upstream.tryEmitNext(ServerSentEvent.builder("{\"choices\":[]}").event("response.created").build()));
            assertTrue(delivering.await(5, TimeUnit.SECONDS));
            if (op == ModelOperation.RESPONSES) {
                assertEquals(reactor.core.publisher.Sinks.EmitResult.OK, upstream.tryEmitNext(ServerSentEvent.builder(
                        "{\"response\":{\"usage\":{\"input_tokens\":5,\"output_tokens\":2,\"total_tokens\":7}}}")
                        .event("response.completed").build()));
            } else {
                assertEquals(reactor.core.publisher.Sinks.EmitResult.OK, upstream.tryEmitNext(ServerSentEvent.builder(
                        "{\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}").build()));
                assertEquals(reactor.core.publisher.Sinks.EmitResult.OK,
                        upstream.tryEmitNext(ServerSentEvent.builder("[DONE]").build()));
            }
            subscription.dispose();
            var event = terminalEvent();
            assertEquals("ABORTED", event.getStatus());
            assertEquals(7, event.getTotalTokens());
            assertEquals(CostStatus.CALCULATED, event.getCostResult().status());
            assertEquals("COMPLETED", JSON.parseObject(JSON.toJSONString(event)).getString("executionOutcome"));
        } finally { release.countDown(); subscription.dispose(); }
    }

    @ParameterizedTest @ValueSource(strings = {"IMAGE_GENERATION", "SPEECH", "TRANSCRIPTION"})
    void realSuccessfulHttpResponseOverLimitCannotReleaseBudgetAsFree(String operation) throws Exception {
        ModelOperation op = ModelOperation.valueOf(operation);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            byte[] bytes = "{\"data\":[{\"b64_json\":\"large-generated-result\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var limiter = new Resilience4jTimeLimiter(5000, 1, 0);
        try {
            primary.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(service, "timeLimiter", limiter);
            ReflectionTestUtils.setField(service, "maxRetries", 3);
            if (op == ModelOperation.IMAGE_GENERATION) {
                var adapter = new OpenAiJsonOperationAdapter(op, "/images/generations");
                ReflectionTestUtils.setField(adapter, "maxImageJsonBytes", 4);
                ReflectionTestUtils.setField(service, "providerAdapter", adapter);
                assertThrows(UpstreamFailureException.class, () -> service.jsonCompletion(context, op,
                        "{\"model\":\"alias\",\"prompt\":\"cat\"}", "real-image-limit", Instant.now()));
            } else {
                var adapter = new OpenAiMediaAdapter(op, UpstreamProtocol.OPENAI_COMPAT);
                ReflectionTestUtils.setField(adapter, "maxOutputBytes", 4);
                ReflectionTestUtils.setField(service, "providerAdapter", adapter);
                AdapterExchange.Request request = op == ModelOperation.SPEECH
                        ? new AdapterExchange.JsonRequest(op, JSON.parseObject("{\"input\":\"hi\",\"voice\":\"alloy\"}"))
                        : new AdapterExchange.MultipartRequest(op, Map.of(), "clip.wav",
                        org.springframework.http.MediaType.parseMediaType("audio/wav"), "audio".getBytes(StandardCharsets.UTF_8));
                assertThrows(UpstreamFailureException.class, () -> service.media(context, op, "alias", request,
                        "real-audio-limit", Instant.now()));
            }
            assertEquals(1, calls.get());
            assertUnknownTerminal("FAIL");
            verifyNoInteractions(fallbackExecutor);
        } finally { limiter.close(); server.stop(0); }
    }

    @ParameterizedTest @ValueSource(strings = {"IMAGE_GENERATION", "SPEECH", "TRANSCRIPTION"})
    void postSendResponseFailurePublishesUnknownCostAndDoesNotRetry(String operation) {
        var failure = new UpstreamFailureException(LlmForwardErrorCodeEnum.REQUEST_TOO_LARGE.message(),
                LlmForwardErrorCodeEnum.REQUEST_TOO_LARGE, false, UpstreamExecutionOutcome.UNKNOWN, false);
        ModelOperation op = ModelOperation.valueOf(operation);
        if (op == ModelOperation.IMAGE_GENERATION) {
            when(timeLimiter.execute(any())).thenThrow(failure);
            assertSame(failure, assertThrows(UpstreamFailureException.class, () -> service.jsonCompletion(
                    context, op, "{\"model\":\"alias\",\"prompt\":\"cat\"}", "image-limit", Instant.now())));
            verify(timeLimiter, times(1)).execute(any());
        } else {
            var media = mock(ProviderAdapter.class, withSettings().extraInterfaces(MediaOperationAdapter.class));
            ReflectionTestUtils.setField(service, "providerAdapter", media);
            when(((MediaOperationAdapter) media).invoke(any(), any())).thenThrow(failure);
            assertSame(failure, assertThrows(UpstreamFailureException.class, () -> service.media(
                    context, op, "alias", new AdapterExchange.JsonRequest(op, new JSONObject()),
                    "audio-limit", Instant.now())));
            verify((MediaOperationAdapter) media, times(1)).invoke(any(), any());
        }
        assertUnknownTerminal("FAIL");
        verifyNoInteractions(fallbackExecutor);
        verify(circuitBreaker, never()).recordFailure(anyString());
    }

    @ParameterizedTest @ValueSource(strings = {"CHAT_COMPLETIONS", "RESPONSES"})
    void interruptedStreamKeepsUnknownExecutionForCancellationAndIdleTimeout(String operation) {
        ModelOperation op = ModelOperation.valueOf(operation);
        when(providerAdapter.stream(any())).thenReturn(Flux.concat(
                Flux.just(ServerSentEvent.builder("{\"choices\":[]}").event("response.created").build()), Flux.never()));
        stream(op, "cancel-unknown").take(1).blockLast();
        assertUnknownTerminal("ABORTED");
        reset(usagePublisher);
        ReflectionTestUtils.setField(service, "streamIdleTimeoutMs", 20L);
        assertThrows(RuntimeException.class, () -> stream(op, "idle-unknown").blockLast());
        assertUnknownTerminal("FAIL");
    }

    @ParameterizedTest @ValueSource(strings = {"CHAT_COMPLETIONS", "RESPONSES"})
    void streamFailurePreservesExplicitRejectionAndUnknownTransportEvidence(String operation) {
        ModelOperation op = ModelOperation.valueOf(operation);
        when(providerAdapter.stream(any())).thenReturn(Flux.error(UpstreamErrors.from(400, null)));
        assertThrows(RuntimeException.class, () -> stream(op, "rejected-stream").blockLast());
        var rejected = terminalEvent();
        assertEquals(CostStatus.NOT_CHARGEABLE, rejected.getCostResult().status());
        reset(usagePublisher);
        when(providerAdapter.stream(any())).thenReturn(Flux.error(UpstreamErrors.timeout()));
        assertThrows(RuntimeException.class, () -> stream(op, "unknown-stream").blockLast());
        assertUnknownTerminal("FAIL");
    }

    @Test void partialChatUsageIsCalculatedButKeepsUncertainRemainder() {
        tokenPricing();
        when(providerAdapter.stream(any())).thenReturn(Flux.concat(Flux.just(ServerSentEvent.builder(
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}").build()), Flux.never()));
        stream(ModelOperation.CHAT_COMPLETIONS, "partial-cancel").take(1).blockLast();
        var event = terminalEvent();
        assertEquals("ABORTED", event.getStatus());
        assertEquals(7, event.getTotalTokens());
        assertEquals(CostStatus.CALCULATED, event.getCostResult().status());
        assertEquals(new BigDecimal("0.000007"), event.getCostResult().estimatedCostFen());
        assertEquals("ESTIMATED", event.getUsageSource());
        assertEquals("UNKNOWN", JSON.parseObject(JSON.toJSONString(event)).getString("executionOutcome"));
    }

    @ParameterizedTest @ValueSource(strings = {"response.failed", "response.incomplete", "response.completed"})
    void responsesTerminalUsageSurvivesFollowingDeliveryError(String terminal) {
        tokenPricing();
        when(providerAdapter.stream(any())).thenReturn(Flux.concat(Flux.just(ServerSentEvent.builder(
                "{\"response\":{\"usage\":{\"input_tokens\":5,\"output_tokens\":2,\"total_tokens\":7}}}")
                .event(terminal).build()), Flux.error(new IllegalStateException("connection closed"))));
        assertThrows(RuntimeException.class, () -> stream(ModelOperation.RESPONSES, "terminal-usage").blockLast());
        var event = terminalEvent();
        assertEquals("FAIL", event.getStatus());
        assertEquals(7, event.getTotalTokens());
        assertEquals(CostStatus.CALCULATED, event.getCostResult().status());
        assertEquals("EXACT", event.getUsageSource());
        assertEquals("COMPLETED", JSON.parseObject(JSON.toJSONString(event)).getString("executionOutcome"));
    }

    @Test void chatDoneUsageSurvivesCancellationAfterUpstreamCompletion() {
        tokenPricing();
        when(providerAdapter.stream(any())).thenReturn(Flux.concat(Flux.just(
                ServerSentEvent.builder("{\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}").build(),
                ServerSentEvent.builder("[DONE]").build()), Flux.never()));
        stream(ModelOperation.CHAT_COMPLETIONS, "done-cancel").take(2).blockLast();
        var event = terminalEvent();
        assertEquals("ABORTED", event.getStatus());
        assertEquals(CostStatus.CALCULATED, event.getCostResult().status());
        assertEquals("EXACT", event.getUsageSource());
        assertEquals("COMPLETED", JSON.parseObject(JSON.toJSONString(event)).getString("executionOutcome"));
    }

    private Flux<ServerSentEvent<String>> stream(ModelOperation operation, String id) {
        return operation == ModelOperation.RESPONSES
                ? service.responsesStream(context, "{\"model\":\"alias\",\"stream\":true}", id, Instant.now())
                : service.chatCompletionStream(context, "{\"model\":\"alias\",\"stream\":true}", id, Instant.now());
    }

    private TokenUsageEvent terminalEvent() {
        var captor = ArgumentCaptor.forClass(TokenUsageEvent.class);
        verify(usagePublisher, times(1)).publish(captor.capture());
        return captor.getValue();
    }

    private void assertUnknownTerminal(String status) {
        var event = terminalEvent();
        assertEquals(status, event.getStatus());
        assertEquals(CostStatus.UNCALCULABLE, event.getCostResult().status());
        assertNull(event.getTotalTokens());
        assertEquals("UNKNOWN", JSON.parseObject(JSON.toJSONString(event)).getString("executionOutcome"));
    }

    private void tokenPricing() {
        when(pricingResolver.resolve(anyLong(), anyLong(), any())).thenReturn(new PricingSnapshot(
                99L, BillingMode.TOKEN, "CNY", PricePeriodType.BASE, null,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, Instant.EPOCH, null));
    }

    private ModelResolver modelResolver;
    private ProviderAdapter providerAdapter;
    private TokenUsageEventPublisher usagePublisher;
    private com.yonagi.verse.service.budget.CostBudgetService budgets;
    private DomainEventPublisher eventPublisher;
    private FallbackExecutor fallbackExecutor;
    private Resilience4jTimeLimiter timeLimiter;
    private PricingResolver pricingResolver;
    private RateLimiter rateLimiter;
    private CircuitBreaker circuitBreaker;
    private LlmServiceMapper serviceMapper;
    private LlmForwardServiceImpl service;
    private LlmServiceDO primary;
    private UserContext context;
    private TenantDO tenant;

    @BeforeEach
    void setUp() {
        modelResolver = mock(ModelResolver.class);
        providerAdapter = mock(ProviderAdapter.class);
        AesUtil aesUtil = mock(AesUtil.class);
        eventPublisher = mock(DomainEventPublisher.class);
        usagePublisher = mock(TokenUsageEventPublisher.class);
        budgets = mock(com.yonagi.verse.service.budget.CostBudgetService.class);
        // 原计费断言继续捕获同一终态，API 的真实生产入口现在是同步结算。
        doAnswer(invocation -> { usagePublisher.publish(invocation.getArgument(0)); return null; }).when(budgets).settle(any());
        TenantMapper tenantMapper = mock(TenantMapper.class);
        serviceMapper = mock(LlmServiceMapper.class);
        rateLimiter = mock(RateLimiter.class);
        circuitBreaker = mock(CircuitBreaker.class);
        fallbackExecutor = mock(FallbackExecutor.class);
        timeLimiter = mock(Resilience4jTimeLimiter.class);
        pricingResolver = mock(PricingResolver.class);
        when(pricingResolver.resolve(anyLong(), anyLong(), any())).thenReturn(PricingSnapshot.unpriced());
        UsageNormalizerRegistry registry = new UsageNormalizerRegistry(List.of(
                new OpenAiUsageNormalizer(), new AnthropicUsageNormalizer(), new GeminiUsageNormalizer(),
                new DeepSeekUsageNormalizer(), new ZhipuUsageNormalizer(), new QwenUsageNormalizer(),
                new DoubaoUsageNormalizer(), new KimiUsageNormalizer(), new MiniMaxUsageNormalizer(),
                new OpenAiCompatibleUsageNormalizer()));
        service = new LlmForwardServiceImpl(modelResolver, providerAdapter, aesUtil, eventPublisher,
                usagePublisher, tenantMapper, serviceMapper, rateLimiter, circuitBreaker, fallbackExecutor,
                timeLimiter, registry, pricingResolver, new CostCalculator(), budgets);
        ReflectionTestUtils.setField(service, "maxRetries", 0);
        ReflectionTestUtils.setField(service, "streamIdleTimeoutMs", 5000L);
        ReflectionTestUtils.setField(service, "costingEnabled", true);

        tenant = new TenantDO();
        tenant.setTenantId(2L);
        tenant.setStatus(1);
        tenant.setAuditEnabled(0);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        primary = llm(10L, "primary", "openai");
        when(modelResolver.resolve(2L, "alias")).thenReturn(primary);
        when(aesUtil.decrypt(anyString())).thenReturn("plain-key");
        context = new UserContext().setUserId(1L).setCurrentTenantId(2L).setApiKeyId(3L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHAT_COMPLETIONS", "RESPONSES", "EMBEDDINGS", "IMAGE_GENERATION", "RERANK"})
    void initialBudgetRejectionNeverSendsChargesRetriesOrChangesHealth(String operation) {
        doThrow(new com.yonagi.verse.service.budget.CostBudgetUnavailableException()).when(budgets).begin(any(), any(), any());
        assertThrows(com.yonagi.verse.service.budget.CostBudgetUnavailableException.class, () -> service.jsonCompletion(
                context, ModelOperation.valueOf(operation), "{\"model\":\"alias\"}", "budget-rejected", Instant.now()));
        verifyNoInteractions(providerAdapter, timeLimiter, fallbackExecutor, circuitBreaker, usagePublisher, rateLimiter);
        verify(budgets, never()).settle(any());
    }

    @Test void streamsRejectBeforeHeadersAndPermitCannotBeSubscribedTwice() {
        doThrow(new com.yonagi.verse.service.budget.CostBudgetUnavailableException()).when(budgets).check(context);
        assertThrows(com.yonagi.verse.service.budget.CostBudgetUnavailableException.class, () -> service.chatCompletionStream(
                context, "{\"model\":\"alias\",\"stream\":true}", "stream-budget", Instant.now()));
        assertThrows(com.yonagi.verse.service.budget.CostBudgetUnavailableException.class, () -> service.responsesStream(
                context, "{\"model\":\"alias\",\"stream\":true}", "responses-budget", Instant.now()));
        verifyNoInteractions(providerAdapter, rateLimiter, circuitBreaker, usagePublisher);
        doNothing().when(budgets).check(context);
        when(providerAdapter.stream(any())).thenReturn(Flux.just(ServerSentEvent.builder("[DONE]").build()));
        var stream = service.chatCompletionStream(context, "{\"model\":\"alias\",\"stream\":true}", "single-permit", Instant.now());
        stream.blockLast();
        assertThrows(IllegalStateException.class, stream::blockLast);
        verify(providerAdapter, times(1)).stream(any()); verify(budgets, times(1)).settle(any());
    }

    @Test void retryBudgetRejectionStopsAttemptsAndPreservesOneOriginalFailureTerminal() {
        ReflectionTestUtils.setField(service, "maxRetries", 3);
        when(timeLimiter.execute(any())).thenThrow(retryableFailure());
        doNothing().doNothing().doThrow(new com.yonagi.verse.service.budget.CostBudgetUnavailableException()).when(budgets).check(context);
        when(budgets.hasUpstream("retry-budget")).thenReturn(true);
        assertThrows(com.yonagi.verse.service.budget.CostBudgetUnavailableException.class, () -> service.chatCompletion(
                context, "{\"model\":\"alias\"}", "retry-budget", Instant.now()));
        verify(timeLimiter, times(1)).execute(any()); verifyNoInteractions(fallbackExecutor);
        verify(circuitBreaker, times(1)).recordFailure("10");
        ArgumentCaptor<TokenUsageEvent> terminal = ArgumentCaptor.forClass(TokenUsageEvent.class);
        verify(budgets, times(1)).settle(terminal.capture());
        assertEquals(CostStatus.NOT_CHARGEABLE, terminal.getValue().getCostResult().status());
    }

    @ParameterizedTest @ValueSource(strings = {"SPEECH", "TRANSCRIPTION"})
    void audioBudgetRejectionOccursBeforeRateLimitOrAdapter(String operation) {
        doThrow(new com.yonagi.verse.service.budget.CostBudgetUnavailableException()).when(budgets).check(context);
        assertThrows(com.yonagi.verse.service.budget.CostBudgetUnavailableException.class, () -> service.media(
                context, ModelOperation.valueOf(operation), "alias", null, "audio-budget", Instant.now()));
        verifyNoInteractions(providerAdapter, rateLimiter, circuitBreaker, usagePublisher);
    }

    @Test
    void blockingSuccessStagesExactlyOneTerminalEvent() {
        when(timeLimiter.execute(any())).thenReturn("""
                {"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
                """);
        Instant startedAt = Instant.parse("2026-09-06T01:02:03Z");

        service.chatCompletion(context, "{\"model\":\"alias\"}", "request-1", startedAt);

        ArgumentCaptor<TokenUsageEvent> captor = ArgumentCaptor.forClass(TokenUsageEvent.class);
        verify(usagePublisher, times(1)).publish(captor.capture());
        assertEquals(10L, captor.getValue().getServiceId());
        assertEquals(startedAt, captor.getValue().getRequestStartedAt());
        assertEquals("SUCCESS", captor.getValue().getStatus());
    }

    @Test
    void fallbackSuccessAttributesOneEventToActualService() {
        LlmServiceDO fallback = llm(11L, "fallback", "deepseek");
        when(fallbackExecutor.resolveFallback(primary)).thenReturn(fallback);
        UpstreamFailureException failure = retryableFailure();
        when(timeLimiter.execute(any())).thenThrow(failure).thenReturn("""
                {"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
                """);

        service.chatCompletion(context, "{\"model\":\"alias\"}", "request-2", Instant.now());

        ArgumentCaptor<TokenUsageEvent> captor = ArgumentCaptor.forClass(TokenUsageEvent.class);
        verify(usagePublisher, times(1)).publish(captor.capture());
        assertEquals(11L, captor.getValue().getServiceId());
        assertEquals("openai-compatible", captor.getValue().getNormalizedUsage().parser());
    }

    @Test
    void retryExhaustionStagesExactlyOneFailureEvent() {
        ReflectionTestUtils.setField(service, "maxRetries", 1);
        when(timeLimiter.execute(any())).thenThrow(retryableFailure());
        when(fallbackExecutor.resolveFallback(primary)).thenReturn(null);

        assertThrows(UpstreamFailureException.class, () -> service.chatCompletion(
                context, "{\"model\":\"alias\"}", "request-3", Instant.now()));

        ArgumentCaptor<TokenUsageEvent> captor = ArgumentCaptor.forClass(TokenUsageEvent.class);
        verify(usagePublisher, times(1)).publish(captor.capture());
        assertEquals("FAIL", captor.getValue().getStatus());
        assertEquals(10L, captor.getValue().getServiceId());
    }

    @Test
    void fallbackFailureStagesExactlyOneEventForFallback() {
        LlmServiceDO fallback = llm(11L, "fallback", "openai");
        when(fallbackExecutor.resolveFallback(primary)).thenReturn(fallback);
        when(timeLimiter.execute(any())).thenThrow(retryableFailure());

        assertThrows(UpstreamFailureException.class, () -> service.chatCompletion(
                context, "{\"model\":\"alias\"}", "request-fallback-fail", Instant.now()));

        ArgumentCaptor<TokenUsageEvent> captor = ArgumentCaptor.forClass(TokenUsageEvent.class);
        verify(usagePublisher, times(1)).publish(captor.capture());
        assertEquals(11L, captor.getValue().getServiceId());
        assertEquals("FAIL", captor.getValue().getStatus());
    }

    @Test
    void streamCompletionAndCompetingCallbacksStageOnce() {
        tokenPricing();
        when(providerAdapter.stream(any())).thenReturn(Flux.just(
                ServerSentEvent.builder("{\"choices\":[]}").build(),
                ServerSentEvent.builder("{\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}").build(),
                ServerSentEvent.builder("[DONE]").build()));

        service.chatCompletionStream(context, "{\"model\":\"alias\",\"stream\":true}",
                "request-4", Instant.now()).blockLast();

        var event = terminalEvent();
        assertEquals("SUCCESS", event.getStatus());
        assertEquals(UpstreamExecutionOutcome.COMPLETED, event.getExecutionOutcome());
        assertEquals("EXACT", event.getUsageSource());
        assertEquals(7, event.getTotalTokens());
        assertEquals(CostStatus.CALCULATED, event.getCostResult().status());
    }

    @Test
    void playgroundStreamUsesTrustedMessagesAndStagesPrivateSource() {
        context.setApiKeyId(null);
        tenant.setAuditEnabled(1);
        when(serviceMapper.selectOne(any())).thenReturn(primary);
        when(providerAdapter.stream(any())).thenReturn(Flux.just(
                ServerSentEvent.builder("{\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}").build(),
                ServerSentEvent.builder("{\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}").build(),
                ServerSentEvent.builder("[DONE]").build()));

        service.playgroundChatStream(context, 10L,
                List.of(new ChatMessage("user", "private question")), "playground-1", Instant.now()).blockLast();

        ArgumentCaptor<ForwardContext> forwarded = ArgumentCaptor.forClass(ForwardContext.class);
        verify(providerAdapter).stream(forwarded.capture());
        assertEquals("https://example.invalid", forwarded.getValue().getApiUrl());
        assertEquals("plain-key", forwarded.getValue().getApiKey());
        assertEquals("upstream", forwarded.getValue().getModelName());
        var forwardedBody = JSON.parseObject(forwarded.getValue().getBody());
        assertEquals("private question", forwardedBody.getJSONArray("messages")
                .getJSONObject(0).getString("content"));
        assertTrue(forwardedBody.getBooleanValue("stream"));

        ArgumentCaptor<TokenUsageEvent> usage = ArgumentCaptor.forClass(TokenUsageEvent.class);
        verify(usagePublisher).publish(usage.capture());
        assertEquals("PLAYGROUND", usage.getValue().getSource());
        assertNull(usage.getValue().getApiKeyId());
        ArgumentCaptor<LlmAuditEvent> audit = ArgumentCaptor.forClass(LlmAuditEvent.class);
        verify(eventPublisher).publish(audit.capture());
        assertEquals("PLAYGROUND", audit.getValue().getSource());
        assertNull(audit.getValue().getPrompt());
        assertNull(audit.getValue().getResponse());
    }

    @Test
    void streamCancellationAndErrorsEachStageOneTerminalEvent() {
        when(providerAdapter.stream(any())).thenReturn(Flux.concat(
                Flux.just(ServerSentEvent.builder("{\"choices\":[]}").build()), Flux.never()));
        service.chatCompletionStream(context, "{\"model\":\"alias\",\"stream\":true}",
                "request-5", Instant.now()).take(1).blockLast();
        verify(usagePublisher, times(1)).publish(any(TokenUsageEvent.class));

        reset(usagePublisher);
        when(providerAdapter.stream(any())).thenReturn(Flux.error(retryableFailure()));
        assertThrows(RuntimeException.class, () -> service.chatCompletionStream(context,
                "{\"model\":\"alias\",\"stream\":true}", "request-6", Instant.now()).blockLast());
        verify(usagePublisher, times(1)).publish(any(TokenUsageEvent.class));
    }

    @Test
    void streamPostFirstByteErrorStagesOnlyOneFailureEvent() {
        when(providerAdapter.stream(any())).thenReturn(Flux.concat(
                Flux.just(ServerSentEvent.builder("{\"choices\":[]}").build()),
                Flux.error(retryableFailure())));

        assertThrows(RuntimeException.class, () -> service.chatCompletionStream(context,
                "{\"model\":\"alias\",\"stream\":true}", "request-7", Instant.now()).blockLast());

        ArgumentCaptor<TokenUsageEvent> captor = ArgumentCaptor.forClass(TokenUsageEvent.class);
        verify(usagePublisher, times(1)).publish(captor.capture());
        assertEquals("FAIL", captor.getValue().getStatus());
    }

    @Test
    void successfulTokenPricedStreamWithoutUsageIsUncalculable() {
        PricingSnapshot priced = new PricingSnapshot(99L, BillingMode.TOKEN, "CNY", PricePeriodType.BASE,
                null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null,
                Instant.EPOCH, null);
        when(pricingResolver.resolve(anyLong(), anyLong(), any())).thenReturn(priced);
        when(providerAdapter.stream(any())).thenReturn(Flux.just(
                ServerSentEvent.builder("{\"choices\":[]}").build(),
                ServerSentEvent.builder("[DONE]").build()));

        service.chatCompletionStream(context, "{\"model\":\"alias\",\"stream\":true}",
                "request-8", Instant.now()).blockLast();

        ArgumentCaptor<TokenUsageEvent> captor = ArgumentCaptor.forClass(TokenUsageEvent.class);
        verify(usagePublisher).publish(captor.capture());
        assertEquals(CostStatus.UNCALCULABLE, captor.getValue().getCostResult().status());
    }

    @Test
    void imageWithoutTokensRecordsCountAndDoesNotFabricateZeroTokens() {
        when(timeLimiter.execute(any())).thenReturn("{\"data\":[{\"url\":\"a\"},{\"url\":\"b\"}]}");
        service.jsonCompletion(context, ModelOperation.IMAGE_GENERATION,
                "{\"model\":\"alias\",\"prompt\":\"cat\"}", "image-1", Instant.now());
        ArgumentCaptor<TokenUsageEvent> captor = ArgumentCaptor.forClass(TokenUsageEvent.class);
        verify(usagePublisher).publish(captor.capture());
        assertEquals(ModelOperation.IMAGE_GENERATION.name(), captor.getValue().getOperation());
        assertEquals(2, captor.getValue().getImageCount());
        assertNull(captor.getValue().getTotalTokens());
        assertEquals(TokenUsageEvent.SOURCE_UNKNOWN, captor.getValue().getUsageSource());
        assertEquals(CostStatus.UNPRICED, captor.getValue().getCostResult().status());
        verify(rateLimiter).settle(any(), eq(0));
    }

    @Test
    void nonTokenImageCanUseRequestPricingWithoutInventingTokens() {
        when(pricingResolver.resolve(anyLong(), anyLong(), any())).thenReturn(new PricingSnapshot(
                99L, BillingMode.REQUEST, "CNY", PricePeriodType.BASE, null,
                null, null, null, new BigDecimal("25"), Instant.EPOCH, null));
        when(timeLimiter.execute(any())).thenReturn("{\"data\":[{\"url\":\"a\"}]}");
        service.jsonCompletion(context, ModelOperation.IMAGE_GENERATION,
                "{\"model\":\"alias\",\"prompt\":\"cat\"}", "image-priced", Instant.now());
        ArgumentCaptor<TokenUsageEvent> captor = ArgumentCaptor.forClass(TokenUsageEvent.class);
        verify(usagePublisher).publish(captor.capture());
        assertNull(captor.getValue().getTotalTokens());
        assertEquals(CostStatus.CALCULATED, captor.getValue().getCostResult().status());
        assertEquals(0, new BigDecimal("25").compareTo(captor.getValue().getCostResult().estimatedCostFen()));
    }

    @Test
    void responsesAuditUsesInputOutputTokensOnlyWhenEnabled() {
        when(timeLimiter.execute(any())).thenReturn(
                "{\"id\":\"r1\",\"usage\":{\"input_tokens\":4,\"output_tokens\":2,\"total_tokens\":6}}");
        String body = "{\"model\":\"alias\",\"input\":\"hello\"}";
        service.jsonCompletion(context, ModelOperation.RESPONSES, body, "response-off", Instant.now());
        verifyNoInteractions(eventPublisher);

        tenant.setAuditEnabled(1);
        service.jsonCompletion(context, ModelOperation.RESPONSES, body, "response-on", Instant.now());
        ArgumentCaptor<com.yonagi.verse.async.api.DomainEvent> captor =
                ArgumentCaptor.forClass(com.yonagi.verse.async.api.DomainEvent.class);
        verify(eventPublisher).publish(captor.capture());
        LlmAuditEvent audit = (LlmAuditEvent) captor.getValue();
        assertEquals(4, audit.getPromptTokens());
        assertEquals(2, audit.getCompletionTokens());
        assertEquals(6, audit.getTotalTokens());
    }

    @Test
    void imageAuditRedactsBase64AndBoundsPreview() {
        tenant.setAuditEnabled(1);
        when(timeLimiter.execute(any())).thenReturn(
                "{\"data\":[{\"b64_json\":\"secret-image-bytes\"}]}");
        service.jsonCompletion(context, ModelOperation.IMAGE_GENERATION,
                "{\"model\":\"alias\",\"prompt\":\"cat\"}", "image-audit", Instant.now());
        ArgumentCaptor<com.yonagi.verse.async.api.DomainEvent> captor =
                ArgumentCaptor.forClass(com.yonagi.verse.async.api.DomainEvent.class);
        verify(eventPublisher).publish(captor.capture());
        LlmAuditEvent audit = (LlmAuditEvent) captor.getValue();
        assertFalse(audit.getResponse().contains("secret-image-bytes"));
        assertTrue(audit.getResponse().contains("[redacted]"));
        assertTrue(audit.getResponse().length() <= 16_384);
    }

    @Test
    void embeddingsFallbackKeepsOperationAndActualService() {
        LlmServiceDO fallback = llm(11L, "fallback", "custom-vendor");
        when(fallbackExecutor.resolveFallback(primary)).thenReturn(fallback);
        when(timeLimiter.execute(any())).thenThrow(retryableFailure()).thenReturn(
                "{\"data\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":0,\"total_tokens\":3}}");
        service.jsonCompletion(context, ModelOperation.EMBEDDINGS,
                "{\"model\":\"alias\",\"input\":\"hi\"}", "embed-1", Instant.now());
        ArgumentCaptor<TokenUsageEvent> captor = ArgumentCaptor.forClass(TokenUsageEvent.class);
        verify(usagePublisher).publish(captor.capture());
        assertEquals(11L, captor.getValue().getServiceId());
        assertEquals(ModelOperation.EMBEDDINGS.name(), captor.getValue().getOperation());
        assertEquals(3, captor.getValue().getTotalTokens());
    }

    @Test
    void incompatibleFallbackIsNotInvokedAndImageIsNeverRetried() {
        LlmServiceDO fallback = llm(11L, "fallback", "openai");
        when(fallbackExecutor.resolveFallback(primary)).thenReturn(fallback);
        doThrow(new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED))
                .when(modelResolver).requireBinding(fallback, ModelOperation.EMBEDDINGS);
        when(timeLimiter.execute(any())).thenThrow(retryableFailure());
        assertThrows(UpstreamFailureException.class, () -> service.jsonCompletion(context,
                ModelOperation.EMBEDDINGS, "{\"model\":\"alias\",\"input\":\"hi\"}",
                "embed-2", Instant.now()));
        verify(timeLimiter, times(1)).execute(any());

        reset(timeLimiter, fallbackExecutor, usagePublisher);
        ReflectionTestUtils.setField(service, "maxRetries", 3);
        when(timeLimiter.execute(any())).thenThrow(retryableFailure());
        assertThrows(UpstreamFailureException.class, () -> service.jsonCompletion(context,
                ModelOperation.IMAGE_GENERATION, "{\"model\":\"alias\",\"prompt\":\"cat\"}",
                "image-2", Instant.now()));
        verify(timeLimiter, times(1)).execute(any());
        verifyNoInteractions(fallbackExecutor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1025", "0", "-1", "1.5", "\"1024\"", "true", "1e100"})
    void apiRejectsInvalidOrExcessiveOutputBeforeRateLimitAndUpstream(String value) {
        primary.setMaxOutputTokens(1024L);
        for (String field : List.of("max_tokens", "max_completion_tokens")) {
            String body = "{\"model\":\"alias\",\"" + field + "\":" + value + "}";
            ClientException blocking = assertThrows(ClientException.class,
                    () -> service.chatCompletion(context, body, "limit-blocking", Instant.now()));
            assertEquals(LlmForwardErrorCodeEnum.OUTPUT_TOKEN_LIMIT_INVALID.code(), blocking.getErrorCode());
            assertThrows(ClientException.class,
                    () -> service.chatCompletionStream(context, body, "limit-stream", Instant.now()));
        }
        verifyNoInteractions(rateLimiter, timeLimiter, providerAdapter, fallbackExecutor);
    }

    @Test
    void chatApiDefaultsToItsLimitAndPreservesExplicitCompletionTokenAlias() {
        primary.setMaxOutputTokens(1024L);
        when(timeLimiter.execute(any())).thenAnswer(i -> ((Callable<?>) i.getArgument(0)).call());
        when(providerAdapter.forward(any())).thenReturn("{}");
        service.chatCompletion(context, "{\"model\":\"alias\"}", "limit-default", Instant.now());
        service.chatCompletion(context, "{\"model\":\"alias\",\"max_completion_tokens\":1024}", "limit-explicit", Instant.now());
        ArgumentCaptor<ForwardContext> forwarded = ArgumentCaptor.forClass(ForwardContext.class);
        verify(providerAdapter, times(2)).forward(forwarded.capture());
        JSONObject defaultBody = JSON.parseObject(forwarded.getAllValues().get(0).getBody());
        assertEquals(1024L, defaultBody.getLongValue("max_tokens"));
        JSONObject explicitBody = JSON.parseObject(forwarded.getAllValues().get(1).getBody());
        assertEquals(1024L, explicitBody.getLongValue("max_completion_tokens"));
        assertFalse(explicitBody.containsKey("max_tokens"));
    }

    @Test
    void apiCannotBypassLimitBySendingBothChatTokenFields() {
        primary.setMaxOutputTokens(1024L);
        assertThrows(ClientException.class, () -> service.chatCompletion(context,
                "{\"model\":\"alias\",\"max_tokens\":1,\"max_completion_tokens\":8192}", "limit-both", Instant.now()));
        verifyNoInteractions(timeLimiter, providerAdapter);
    }

    @Test
    void streamingApiAndPlaygroundUseIndependentLimitsAndDefaults() {
        primary.setMaxOutputTokens(1024L);
        primary.setProviderSettings("{\"playground\":\"{\\\"maxTokens\\\":8192}\"}");
        when(serviceMapper.selectOne(any())).thenReturn(primary);
        when(providerAdapter.stream(any())).thenReturn(Flux.just(ServerSentEvent.builder("[DONE]").build()));
        service.chatCompletionStream(context, "{\"model\":\"alias\"}", "api-default", Instant.now()).blockLast();
        context.setApiKeyId(null);
        service.playgroundChatStream(context, 10L, List.of(new ChatMessage("user", "hi")),
                Map.of("max_tokens", 8192), "pg-explicit", Instant.now()).blockLast();
        service.playgroundChatStream(context, 10L, List.of(new ChatMessage("user", "hi")),
                Map.of(), "pg-default", Instant.now()).blockLast();
        assertThrows(ClientException.class, () -> service.playgroundChatStream(context, 10L,
                List.of(new ChatMessage("user", "hi")), Map.of("max_tokens", 8193), "pg-excess", Instant.now()));
        ArgumentCaptor<ForwardContext> forwarded = ArgumentCaptor.forClass(ForwardContext.class);
        verify(providerAdapter, times(3)).stream(forwarded.capture());
        assertEquals(List.of(1024L, 8192L, 8192L), forwarded.getAllValues().stream()
                .map(f -> JSON.parseObject(f.getBody()).getLong("max_tokens")).toList());
    }

    @Test
    void responsesApiUsesOutputTokenLimitInBothModes() {
        primary.setMaxOutputTokens(1024L);
        String excessive = "{\"model\":\"alias\",\"max_output_tokens\":1025}";
        assertThrows(ClientException.class, () -> service.jsonCompletion(context, ModelOperation.RESPONSES,
                excessive, "responses-excess", Instant.now()));
        assertThrows(ClientException.class, () -> service.responsesStream(context,
                excessive, "responses-stream-excess", Instant.now()));
        when(timeLimiter.execute(any())).thenAnswer(i -> ((Callable<?>) i.getArgument(0)).call());
        when(providerAdapter.forward(any())).thenReturn("{}");
        when(providerAdapter.stream(any())).thenReturn(Flux.empty());
        service.jsonCompletion(context, ModelOperation.RESPONSES,
                "{\"model\":\"alias\"}", "responses-default", Instant.now());
        service.responsesStream(context, "{\"model\":\"alias\"}", "responses-stream-default", Instant.now()).blockLast();
        ArgumentCaptor<ForwardContext> blocking = ArgumentCaptor.forClass(ForwardContext.class);
        ArgumentCaptor<ForwardContext> streaming = ArgumentCaptor.forClass(ForwardContext.class);
        verify(providerAdapter).forward(blocking.capture());
        verify(providerAdapter).stream(streaming.capture());
        for (ForwardContext forwarded : List.of(blocking.getValue(), streaming.getValue())) {
            JSONObject body = JSON.parseObject(forwarded.getBody());
            assertEquals(1024L, body.getLongValue("max_output_tokens"));
            assertFalse(body.containsKey("max_tokens"));
        }
    }

    @Test
    void fallbackRevalidatesItsOwnLimitAndStagesOneFailure() {
        primary.setMaxOutputTokens(8192L);
        LlmServiceDO fallback = llm(11L, "fallback", "openai"); fallback.setMaxOutputTokens(1024L);
        when(fallbackExecutor.resolveFallback(primary)).thenReturn(fallback);
        when(timeLimiter.execute(any())).thenThrow(retryableFailure());
        assertThrows(ClientException.class, () -> service.chatCompletion(context,
                "{\"model\":\"alias\",\"max_tokens\":4096}", "fallback-limit", Instant.now()));
        verify(timeLimiter, times(1)).execute(any());
        ArgumentCaptor<TokenUsageEvent> usage = ArgumentCaptor.forClass(TokenUsageEvent.class);
        verify(usagePublisher).publish(usage.capture());
        assertEquals(11L, usage.getValue().getServiceId());
        assertEquals("FAIL", usage.getValue().getStatus());
    }

    @Test
    void outputLimitDoesNotInjectTokenFieldsIntoEmbeddings() {
        primary.setMaxOutputTokens(1024L);
        when(timeLimiter.execute(any())).thenAnswer(i -> ((Callable<?>) i.getArgument(0)).call());
        when(providerAdapter.forward(any())).thenReturn("{}");
        String body = "{\"model\":\"alias\",\"input\":\"hi\"}";
        service.jsonCompletion(context, ModelOperation.EMBEDDINGS, body, "embedding-limit", Instant.now());
        ArgumentCaptor<ForwardContext> forwarded = ArgumentCaptor.forClass(ForwardContext.class);
        verify(providerAdapter).forward(forwarded.capture());
        assertEquals(body, forwarded.getValue().getBody());
    }

    @Test void unknownTimeoutNeverRetriesOrFallsBackAndDoesNotAssertFreeExecution() {
        ReflectionTestUtils.setField(service, "maxRetries", 1);
        var timeout = new UpstreamFailureException("timeout", LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT, true);
        when(timeLimiter.execute(any())).thenThrow(timeout);

        assertThrows(UpstreamFailureException.class, () -> service.chatCompletion(context,
                "{\"model\":\"alias\"}", "uncertain", Instant.now()));

        verify(timeLimiter, times(1)).execute(any());
        verifyNoInteractions(fallbackExecutor);
        var event = ArgumentCaptor.forClass(TokenUsageEvent.class);
        verify(budgets).settle(event.capture());
        assertEquals(CostStatus.UNCALCULABLE, event.getValue().getCostResult().status());
        assertNull(event.getValue().getTotalTokens());
    }

    @Test void capacityRejectionAbandonsWithoutSendingChargingOrChangingHealth() {
        when(timeLimiter.execute(any())).thenThrow(new UpstreamFailureException("capacity",
                LlmForwardErrorCodeEnum.UPSTREAM_CAPACITY_EXCEEDED, false, UpstreamExecutionOutcome.NOT_SENT));
        assertThrows(UpstreamFailureException.class, () -> service.chatCompletion(context,
                "{\"model\":\"alias\"}", "capacity", Instant.now()));
        verify(budgets).abandon(context, "capacity");
        verify(budgets, never()).sent(anyString()); verify(budgets, never()).settle(any());
        verify(circuitBreaker, never()).recordFailure(anyString());
        verifyNoInteractions(providerAdapter, fallbackExecutor, usagePublisher);
    }

    @Test void explicitRejectionCanRetryAndEachAttemptConsumesRateLimit() {
        ReflectionTestUtils.setField(service, "maxRetries", 1);
        when(timeLimiter.execute(any())).thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call());
        when(providerAdapter.forward(any())).thenThrow(UpstreamErrors.from(429, null))
                .thenReturn("{\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}");
        service.chatCompletion(context, "{\"model\":\"alias\"}", "safe-retry", Instant.now());
        verify(providerAdapter, times(2)).forward(any()); verify(budgets, times(2)).sent("safe-retry");
        verify(rateLimiter, times(2)).check(any()); verify(budgets, times(1)).settle(any());
        verifyNoInteractions(fallbackExecutor);
    }

    @Test void realLimiterPreservesUpstreamValidationErrorAndAvoidsRetry() {
        var realLimiter = new Resilience4jTimeLimiter(1000, 1, 0);
        ReflectionTestUtils.setField(service, "timeLimiter", realLimiter);
        var rejected = UpstreamErrors.from(400, "{\"error\":{\"message\":\"invalid input\"}}");
        when(providerAdapter.forward(any())).thenThrow(rejected);
        try {
            assertSame(rejected, assertThrows(UpstreamFailureException.class, () -> service.chatCompletion(context,
                    "{\"model\":\"alias\"}", "invalid-input", Instant.now())));
            verify(providerAdapter, times(1)).forward(any()); verifyNoInteractions(fallbackExecutor);
            verify(circuitBreaker, never()).recordFailure(anyString());
            var event = ArgumentCaptor.forClass(TokenUsageEvent.class); verify(budgets).settle(event.capture());
            assertEquals(CostStatus.NOT_CHARGEABLE, event.getValue().getCostResult().status());
        } finally { realLimiter.close(); }
    }

    @Test void realHttpTimeoutLeavesOneExecutionAndOneUnknownFeeTerminal() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var serverWorkers = Executors.newFixedThreadPool(2);
        var caller = Executors.newSingleThreadExecutor();
        var calls = new AtomicInteger();
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        server.setExecutor(serverWorkers);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes(); calls.incrementAndGet(); arrived.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("probe release deadline");
                byte[] response = "{\"usage\":{\"total_tokens\":7}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); completed.countDown(); }
        });
        server.start();
        var realLimiter = new Resilience4jTimeLimiter(500, 1, 0);
        primary.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "timeLimiter", realLimiter);
        ReflectionTestUtils.setField(service, "providerAdapter", new OpenAiCompatibleAdapter(1000, 3000));
        ReflectionTestUtils.setField(service, "maxRetries", 1);
        try {
            var result = caller.submit(() -> assertThrows(UpstreamFailureException.class, () -> service.chatCompletion(
                    context, "{\"model\":\"alias\"}", "http-unknown", Instant.now())));
            assertTrue(arrived.await(2, TimeUnit.SECONDS));
            assertEquals(UpstreamExecutionOutcome.UNKNOWN, result.get(2, TimeUnit.SECONDS).getExecutionOutcome());
            assertEquals(1, calls.get()); assertEquals(1L, completed.getCount());
            var rejected = assertThrows(UpstreamFailureException.class, () -> realLimiter.execute(() -> "duplicate"));
            assertEquals(LlmForwardErrorCodeEnum.UPSTREAM_CAPACITY_EXCEEDED.code(), rejected.getErrorCode());
            verifyNoInteractions(fallbackExecutor); verify(budgets).sent("http-unknown");
            var event = ArgumentCaptor.forClass(TokenUsageEvent.class); verify(budgets).settle(event.capture());
            assertEquals(CostStatus.UNCALCULABLE, event.getValue().getCostResult().status());
            release.countDown(); assertTrue(completed.await(2, TimeUnit.SECONDS));
            verify(budgets, times(1)).settle(any()); assertEquals(1, calls.get());
        } finally {
            release.countDown(); caller.shutdownNow(); realLimiter.close(); server.stop(0); serverWorkers.shutdownNow();
        }
    }

    private LlmServiceDO llm(Long id, String name, String provider) {
        return LlmServiceDO.builder().serviceId(id).tenantId(2L).name(name).provider(provider)
                .apiUrl("https://example.invalid").apiKey("encrypted").modelName("upstream").status(1).build();
    }

    private UpstreamFailureException retryableFailure() {
        // 重试夹具必须明确表示请求被拒绝，不能用未知执行结果模拟安全重试。
        return new UpstreamFailureException("upstream rejected", LlmForwardErrorCodeEnum.UPSTREAM_ERROR,
                true, UpstreamExecutionOutcome.REJECTED);
    }
}
