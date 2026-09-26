package com.yonagi.verse.service.impl;

import com.alibaba.fastjson2.JSON;
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
import com.yonagi.verse.service.pricing.CostCalculator;
import com.yonagi.verse.service.pricing.PricingResolver;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import com.yonagi.verse.service.usage.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmForwardUsagePublicationTest {
    private ModelResolver modelResolver;
    private ProviderAdapter providerAdapter;
    private TokenUsageEventPublisher usagePublisher;
    private DomainEventPublisher eventPublisher;
    private FallbackExecutor fallbackExecutor;
    private Resilience4jTimeLimiter timeLimiter;
    private PricingResolver pricingResolver;
    private RateLimiter rateLimiter;
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
        TenantMapper tenantMapper = mock(TenantMapper.class);
        serviceMapper = mock(LlmServiceMapper.class);
        rateLimiter = mock(RateLimiter.class);
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
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
                timeLimiter, registry, pricingResolver, new CostCalculator());
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
        when(providerAdapter.stream(any())).thenReturn(Flux.just(
                ServerSentEvent.builder("{\"choices\":[]}").build(),
                ServerSentEvent.builder("{\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}").build(),
                ServerSentEvent.builder("[DONE]").build()));

        service.chatCompletionStream(context, "{\"model\":\"alias\",\"stream\":true}",
                "request-4", Instant.now()).blockLast();

        verify(usagePublisher, times(1)).publish(any(TokenUsageEvent.class));
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

    private LlmServiceDO llm(Long id, String name, String provider) {
        return LlmServiceDO.builder().serviceId(id).tenantId(2L).name(name).provider(provider)
                .apiUrl("https://example.invalid").apiKey("encrypted").modelName("upstream").status(1).build();
    }

    private UpstreamFailureException retryableFailure() {
        return new UpstreamFailureException("upstream failed", LlmForwardErrorCodeEnum.FORWARD_FAILED, true);
    }
}
