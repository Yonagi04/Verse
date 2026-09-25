package com.yonagi.verse.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.async.api.TokenUsageEventPublisher;
import com.yonagi.verse.async.api.DomainEvent;
import com.yonagi.verse.async.event.LlmAuditEvent;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.resilience.api.CircuitBreaker;
import com.yonagi.verse.resilience.api.FallbackExecutor;
import com.yonagi.verse.resilience.api.RateLimitContext;
import com.yonagi.verse.resilience.api.RateLimiter;
import com.yonagi.verse.resilience.impl.Resilience4jTimeLimiter;
import com.yonagi.verse.service.LlmForwardService;
import com.yonagi.verse.service.forward.ForwardContext;
import com.yonagi.verse.service.forward.AdapterExchange;
import com.yonagi.verse.service.forward.MediaOperationAdapter;
import com.yonagi.verse.service.forward.ModelResolver;
import com.yonagi.verse.service.forward.ProviderAdapter;
import com.yonagi.verse.service.forward.StreamResponseAccumulator;
import com.yonagi.verse.service.forward.UpstreamFailureException;
import com.yonagi.verse.service.pricing.CostCalculator;
import com.yonagi.verse.service.pricing.CostResult;
import com.yonagi.verse.service.pricing.PricingResolver;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import com.yonagi.verse.service.usage.UsageBreakdown;
import com.yonagi.verse.service.usage.UsageNormalizerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM 转发服务实现 — 非事务同步阻塞编排：解析模型 → 限流 → 熔断 → 转发 → 失败降级 → 结算/发 Token 事件。
 *
 * @author Yonagi
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmForwardServiceImpl implements LlmForwardService {

    private final ModelResolver modelResolver;
    private final ProviderAdapter providerAdapter;
    private final AesUtil aesUtil;
    private final DomainEventPublisher eventPublisher;
    private final TokenUsageEventPublisher tokenUsageEventPublisher;
    private final TenantMapper tenantMapper;
    private final LlmServiceMapper llmServiceMapper;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;
    private final FallbackExecutor fallbackExecutor;
    private final Resilience4jTimeLimiter timeLimiter;
    private final UsageNormalizerRegistry usageNormalizerRegistry;
    private final PricingResolver pricingResolver;
    private final CostCalculator costCalculator;

    /**
     * 同模型短重试次数（不含首次调用），来自 {@code verse.llm.upstream.max-retries}
     */
    @Value("${verse.llm.upstream.max-retries:1}")
    private int maxRetries;

    /**
     * 流式 chunk 间空闲超时（毫秒），区别于阻塞链路的 time-limit-ms 总耗时硬超时
     */
    @Value("${verse.llm.upstream.stream-idle-timeout-ms:120000}")
    private long streamIdleTimeoutMs;

    @Value("${verse.llm.costing.enabled:true}")
    private boolean costingEnabled;

    @Override
    public String chatCompletion(UserContext ctx, String body, String requestId, Instant requestStartedAt) {
        return jsonCompletion(ctx, ModelOperation.CHAT_COMPLETIONS, body, requestId, requestStartedAt);
    }

    /** JSON 能力共享 Chat 的租户、限流、熔断、降级和终态编排。 */
    public String jsonCompletion(UserContext ctx, ModelOperation operation, String body,
                                 String requestId, Instant requestStartedAt) {
        if (ctx == null || ctx.getCurrentTenantId() == null) {
            throw new ClientException(LlmForwardErrorCodeEnum.API_KEY_INVALID);
        }
        Long tenantId = ctx.getCurrentTenantId();
        TenantDO tenant = validateTenant(tenantId);

        JSONObject bodyJson = JSON.parseObject(body);
        if (bodyJson == null) {
            throw new ClientException(LlmForwardErrorCodeEnum.MODEL_NOT_FOUND);
        }
        String model = bodyJson.getString("model");
        if (!StringUtils.hasText(model)) {
            throw new ClientException(LlmForwardErrorCodeEnum.MODEL_NOT_FOUND);
        }

        LlmServiceDO primary = modelResolver.resolve(tenantId, model);
        modelResolver.requireBinding(primary, operation);
        RateLimitContext primaryCtx = buildRateContext(ctx, tenant, primary);

        LlmServiceDO actualService = primary;
        RateLimitContext actualCtx = primaryCtx;
        String response;
        long start = requestStartedAt.toEpochMilli();
        try {
            response = forwardWithResilience(primary, primaryCtx, body, operation);
        } catch (UpstreamFailureException e) {
            if (!e.isRetryable() || operation == ModelOperation.IMAGE_GENERATION
                    || operation == ModelOperation.SPEECH) {
                publishTokenUsage(
                        ctx, tenant.getTenantId(), primary, null,
                        requestId, LlmAuditEvent.STATUS_FAIL, start, operation
                );
                publishAudit(ctx, tenant, primary, body, null, requestId, start,
                        LlmAuditEvent.STATUS_FAIL, e.getErrorCode(), operation);
                throw e;
            }
            // 主模型可重试失败/熔断打开 → 降级到备用模型
            LlmServiceDO fallbackService = fallbackExecutor.resolveFallback(primary);
            if (fallbackService != null) {
                try {
                    if (!tenantId.equals(fallbackService.getTenantId())
                            || !Integer.valueOf(1).equals(fallbackService.getStatus())
                            || Integer.valueOf(1).equals(fallbackService.getDelFlag())) {
                        fallbackService = null;
                    } else {
                        modelResolver.requireBinding(fallbackService, operation);
                    }
                } catch (ClientException unsupported) {
                    fallbackService = null;
                }
            }
            if (fallbackService == null) {
                // 无备用模型：透传主模型真实错误（含上游错误信息/熔断状态），不误报为「模型已熔断」
                publishTokenUsage(
                        ctx, tenant.getTenantId(), primary, null,
                        requestId, LlmAuditEvent.STATUS_FAIL, start, operation
                );
                publishAudit(ctx, tenant, primary, body, null, requestId, start,
                        LlmAuditEvent.STATUS_FAIL, e.getErrorCode(), operation);
                throw e;
            }
            log.warn("[llm-forward] 主模型转发失败，降级到备用模型: primary={}, fallback={}, reason={}",
                    primary.getServiceId(), fallbackService.getServiceId(), e.getMessage());
            actualService = fallbackService;
            actualCtx = buildRateContext(ctx, tenant, fallbackService);
            try {
                // 备用模型走完整韧性流程（限流 + 熔断 + 转发）
                response = forwardWithResilience(fallbackService, actualCtx, body, operation);
            } catch (UpstreamFailureException e2) {
                // 备用模型也失败：透传备用模型真实错误
                log.warn("[llm-forward] 备用模型转发失败: fallback={}, reason={}",
                        fallbackService.getServiceId(), e2.getMessage());
                publishTokenUsage(
                        ctx, tenant.getTenantId(), fallbackService, null,
                        requestId, LlmAuditEvent.STATUS_FAIL, start, operation
                );
                publishAudit(ctx, tenant, fallbackService, body, null, requestId, start,
                        LlmAuditEvent.STATUS_FAIL, e2.getErrorCode(), operation);
                throw e2;
            }
        }

        return settleAndPublish(ctx, tenant, actualService, actualCtx, body, response, requestId, start, operation);
    }

    @Override
    public Flux<ServerSentEvent<String>> chatCompletionStream(UserContext ctx, String body,
                                                               String requestId, Instant requestStartedAt) {
        // 同步序言：校验/解析/限流/熔断，任一失败抛异常 → controller 转 OpenAI JSON error（响应头未发）
        if (ctx == null || ctx.getCurrentTenantId() == null) {
            throw new ClientException(LlmForwardErrorCodeEnum.API_KEY_INVALID);
        }
        Long tenantId = ctx.getCurrentTenantId();
        TenantDO tenant = validateTenant(tenantId);

        JSONObject bodyJson = JSON.parseObject(body);
        if (bodyJson == null || !StringUtils.hasText(bodyJson.getString("model"))) {
            throw new ClientException(LlmForwardErrorCodeEnum.MODEL_NOT_FOUND);
        }

        LlmServiceDO service = modelResolver.resolve(tenantId, bodyJson.getString("model"));
        modelResolver.requireBinding(service, ModelOperation.CHAT_COMPLETIONS);
        RateLimitContext rateCtx = buildRateContext(ctx, tenant, service);
        rateLimiter.check(rateCtx);

        String serviceId = String.valueOf(service.getServiceId());
        if (circuitBreaker.isOpen(serviceId)) {
            throw new UpstreamFailureException(LlmForwardErrorCodeEnum.MODEL_CIRCUIT_OPEN.message(),
                    LlmForwardErrorCodeEnum.MODEL_CIRCUIT_OPEN, true);
        }

        String realApiKey = service.getApiKey() == null ? null : aesUtil.decrypt(service.getApiKey());
        ForwardContext forwardContext = ForwardContext.builder()
                .apiUrl(service.getApiUrl())
                .apiKey(realApiKey)
                .modelName(service.getModelName())
                .body(body)
                .operation(ModelOperation.CHAT_COMPLETIONS)
                .protocol(modelResolver.protocolFor(service, ModelOperation.CHAT_COMPLETIONS))
                .provider(service.getProvider())
                .providerSettings(service.getProviderSettings())
                .build();

        boolean auditEnabled = Integer.valueOf(1).equals(tenant.getAuditEnabled());

        // 每次订阅独立持有首块状态和响应聚合器，避免冷 Flux 重复订阅时共享可变状态。
        return Flux.defer(() -> {
            long start = requestStartedAt.toEpochMilli();
            AtomicBoolean firstChunk = new AtomicBoolean(false);
            AtomicBoolean finalized = new AtomicBoolean(false);
            StreamResponseAccumulator accumulator = new StreamResponseAccumulator(auditEnabled);

            // 惰性 Flux：订阅时才连上游；流式无重试/降级，仅 pre-flight + 首字节前失败记录
            return providerAdapter.stream(forwardContext)
                    .timeout(Duration.ofMillis(streamIdleTimeoutMs))
                    .publishOn(Schedulers.boundedElastic())
                    .doOnNext(sse -> {
                        if (firstChunk.compareAndSet(false, true)) {
                            circuitBreaker.recordSuccess(serviceId);
                        }
                        accumulator.accept(sse.data());
                    })
                    .doOnComplete(() -> finalizeStreamOnce(finalized, ctx, tenant, service, rateCtx, body,
                            requestId, start, accumulator, LlmAuditEvent.STATUS_SUCCESS, null))
                    .doOnCancel(() -> finalizeStreamOnce(finalized, ctx, tenant, service, rateCtx, body,
                            requestId, start, accumulator, LlmAuditEvent.STATUS_ABORTED, null))
                    .doOnError(e -> {
                        if (!firstChunk.get()) {
                            circuitBreaker.recordFailure(serviceId);
                        }
                        finalizeStreamOnce(finalized, ctx, tenant, service, rateCtx, body, requestId,
                                start, accumulator, LlmAuditEvent.STATUS_FAIL, errorCode(e));
                    });
        });
    }

    @Override
    public Flux<ServerSentEvent<String>> responsesStream(UserContext ctx, String body,
                                                          String requestId, Instant requestStartedAt) {
        if (ctx == null || ctx.getCurrentTenantId() == null) {
            throw new ClientException(LlmForwardErrorCodeEnum.API_KEY_INVALID);
        }
        TenantDO tenant = validateTenant(ctx.getCurrentTenantId());
        JSONObject json = JSON.parseObject(body);
        if (json == null || !StringUtils.hasText(json.getString("model"))) {
            throw new ClientException(LlmForwardErrorCodeEnum.MODEL_NOT_FOUND);
        }
        LlmServiceDO service = modelResolver.resolve(tenant.getTenantId(), json.getString("model"));
        var protocol = modelResolver.protocolFor(service, ModelOperation.RESPONSES);
        RateLimitContext rateCtx = buildRateContext(ctx, tenant, service);
        rateLimiter.check(rateCtx);
        String serviceId = String.valueOf(service.getServiceId());
        if (circuitBreaker.isOpen(serviceId)) {
            throw new UpstreamFailureException(LlmForwardErrorCodeEnum.MODEL_CIRCUIT_OPEN.message(),
                    LlmForwardErrorCodeEnum.MODEL_CIRCUIT_OPEN, true);
        }
        ForwardContext forwardContext = ForwardContext.builder()
                .apiUrl(service.getApiUrl())
                .apiKey(service.getApiKey() == null ? null : aesUtil.decrypt(service.getApiKey()))
                .modelName(service.getModelName()).body(body)
                .operation(ModelOperation.RESPONSES).protocol(protocol)
                .provider(service.getProvider()).providerSettings(service.getProviderSettings()).build();

        return Flux.defer(() -> {
            AtomicBoolean firstEvent = new AtomicBoolean();
            AtomicBoolean finalized = new AtomicBoolean();
            AtomicReference<JSONObject> terminal = new AtomicReference<>();
            AtomicReference<String> terminalStatus = new AtomicReference<>(LlmAuditEvent.STATUS_ABORTED);
            long start = requestStartedAt.toEpochMilli();
            return providerAdapter.stream(forwardContext)
                    .timeout(Duration.ofMillis(streamIdleTimeoutMs))
                    .publishOn(Schedulers.boundedElastic())
                    .doOnNext(event -> {
                        if (firstEvent.compareAndSet(false, true)) circuitBreaker.recordSuccess(serviceId);
                        String name = event.event();
                        if ("response.completed".equals(name) || "response.failed".equals(name)
                                || "response.incomplete".equals(name)) {
                            try {
                                JSONObject envelope = JSON.parseObject(event.data());
                                terminal.set(envelope == null ? null : envelope.getJSONObject("response"));
                            } catch (RuntimeException ignored) {
                                terminal.set(null);
                            }
                            terminalStatus.set("response.completed".equals(name) ? LlmAuditEvent.STATUS_SUCCESS
                                    : "response.failed".equals(name) ? LlmAuditEvent.STATUS_FAIL
                                    : LlmAuditEvent.STATUS_ABORTED);
                        }
                    })
                    .doOnComplete(() -> finalizeResponsesStreamOnce(finalized, ctx, tenant, service, rateCtx,
                            body, requestId, start, terminal.get(), terminalStatus.get()))
                    .doOnCancel(() -> finalizeResponsesStreamOnce(finalized, ctx, tenant, service, rateCtx,
                            body, requestId, start, terminal.get(), LlmAuditEvent.STATUS_ABORTED))
                    .doOnError(error -> {
                        if (!firstEvent.get()) circuitBreaker.recordFailure(serviceId);
                        finalizeResponsesStreamOnce(finalized, ctx, tenant, service, rateCtx,
                                body, requestId, start, terminal.get(), LlmAuditEvent.STATUS_FAIL);
                    });
        });
    }

    @Override
    public AdapterExchange.Result media(UserContext ctx, ModelOperation operation, String modelAlias,
                                        AdapterExchange.Request request, String requestId,
                                        Instant requestStartedAt) {
        if (ctx == null || ctx.getCurrentTenantId() == null) {
            throw new ClientException(LlmForwardErrorCodeEnum.API_KEY_INVALID);
        }
        TenantDO tenant = validateTenant(ctx.getCurrentTenantId());
        LlmServiceDO service = modelResolver.resolve(tenant.getTenantId(), modelAlias);
        var protocol = modelResolver.protocolFor(service, operation);
        RateLimitContext rateCtx = buildRateContext(ctx, tenant, service);
        rateLimiter.check(rateCtx);
        String serviceId = String.valueOf(service.getServiceId());
        if (circuitBreaker.isOpen(serviceId)) {
            throw new UpstreamFailureException(LlmForwardErrorCodeEnum.MODEL_CIRCUIT_OPEN.message(),
                    LlmForwardErrorCodeEnum.MODEL_CIRCUIT_OPEN, true);
        }
        ForwardContext context = ForwardContext.builder().apiUrl(service.getApiUrl())
                .apiKey(service.getApiKey() == null ? null : aesUtil.decrypt(service.getApiKey()))
                .modelName(service.getModelName()).operation(operation).protocol(protocol)
                .provider(service.getProvider()).providerSettings(service.getProviderSettings()).build();
        if (!(providerAdapter instanceof MediaOperationAdapter mediaAdapter)) {
            throw new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
        }
        String safeRequest = mediaPreview(request);
        long start = requestStartedAt.toEpochMilli();
        try {
            AdapterExchange.Result result = mediaAdapter.invoke(context, request);
            circuitBreaker.recordSuccess(serviceId);
            JSONObject envelope = new JSONObject();
            if (result.usage().raw() != null) envelope.put("usage", result.usage().raw());
            if (result.usage().audioDurationMs() != null) {
                envelope.put("verse_audio_duration_ms", result.usage().audioDurationMs());
            }
            UsageBreakdown normalized = usageNormalizerRegistry.normalize(service.getProvider(), envelope);
            settleRateLimitSafely(rateCtx, safeTokenCount(normalized.totalTokens()));
            publishTokenUsage(ctx, tenant.getTenantId(), service, envelope, requestId,
                    LlmAuditEvent.STATUS_SUCCESS, start, operation);
            publishAudit(ctx, tenant, service, safeRequest, JSON.toJSONString(result instanceof AdapterExchange.BinaryResult binary
                    ? binary.auditMetadata() : java.util.Map.of()), requestId, start,
                    LlmAuditEvent.STATUS_SUCCESS, null, operation);
            return result;
        } catch (UpstreamFailureException e) {
            if (e.isRetryable()) circuitBreaker.recordFailure(serviceId);
            publishTokenUsage(ctx, tenant.getTenantId(), service, null, requestId,
                    LlmAuditEvent.STATUS_FAIL, start, operation);
            publishAudit(ctx, tenant, service, safeRequest, null, requestId, start,
                    LlmAuditEvent.STATUS_FAIL, e.getErrorCode(), operation);
            throw e;
        } catch (RuntimeException e) {
            publishTokenUsage(ctx, tenant.getTenantId(), service, null, requestId,
                    LlmAuditEvent.STATUS_FAIL, start, operation);
            publishAudit(ctx, tenant, service, safeRequest, null, requestId, start,
                    LlmAuditEvent.STATUS_FAIL, errorCode(e), operation);
            throw e;
        }
    }

    private String mediaPreview(AdapterExchange.Request request) {
        JSONObject preview = new JSONObject();
        preview.put("operation", request.operation().name());
        if (request instanceof AdapterExchange.MultipartRequest multipart) {
            preview.put("filename", multipart.filename());
            preview.put("mime", multipart.fileType().toString());
            preview.put("bytes", multipart.file().length);
        } else if (request instanceof AdapterExchange.JsonRequest json) {
            preview.put("voice", json.body().getString("voice"));
            preview.put("format", json.body().getString("response_format"));
            preview.put("inputLength", json.body().getString("input") == null
                    ? 0 : json.body().getString("input").length());
        }
        return JSON.toJSONString(preview);
    }

    private void finalizeResponsesStreamOnce(AtomicBoolean finalized, UserContext ctx, TenantDO tenant,
                                             LlmServiceDO service, RateLimitContext rateCtx,
                                             String body, String requestId, long start,
                                             JSONObject response, String status) {
        if (!finalized.compareAndSet(false, true)) return;
        UsageBreakdown normalized = usageNormalizerRegistry.normalize(service.getProvider(), response);
        settleRateLimitSafely(rateCtx, safeTokenCount(normalized.totalTokens()));
        String source = usageObject(response) == null ? TokenUsageEvent.SOURCE_UNKNOWN
                : LlmAuditEvent.STATUS_SUCCESS.equals(status)
                ? TokenUsageEvent.SOURCE_EXACT : TokenUsageEvent.SOURCE_ESTIMATED;
        publishTokenUsage(ctx, tenant.getTenantId(), service, response, requestId, status,
                source, start, ModelOperation.RESPONSES);
        publishAudit(ctx, tenant, service, body, response == null ? null : response.toJSONString(),
                requestId, start, status, null, ModelOperation.RESPONSES);
    }

    /**
     * 单次转发 + 韧性包裹：限流检查 → 熔断判断 → 上游调用（超时 + 短重试）→ 记录成功/失败。
     */
    private String forwardWithResilience(LlmServiceDO service, RateLimitContext rateCtx,
                                         String body, ModelOperation operation) {
        rateLimiter.check(rateCtx);

        String serviceId = String.valueOf(service.getServiceId());
        if (circuitBreaker.isOpen(serviceId)) {
            throw new UpstreamFailureException(LlmForwardErrorCodeEnum.MODEL_CIRCUIT_OPEN.message(),
                    LlmForwardErrorCodeEnum.MODEL_CIRCUIT_OPEN, true);
        }

        String realApiKey = service.getApiKey() == null ? null : aesUtil.decrypt(service.getApiKey());
        ForwardContext forwardContext = ForwardContext.builder()
                .apiUrl(service.getApiUrl())
                .apiKey(realApiKey)
                .modelName(service.getModelName())
                .body(body)
                .operation(operation)
                .protocol(modelResolver.protocolFor(service, operation))
                .provider(service.getProvider())
                .providerSettings(service.getProviderSettings())
                .build();

        // 图片和语音生成可能已在上游执行，不能对不确定结果自动重试。
        int maxAttempts = operation == ModelOperation.IMAGE_GENERATION || operation == ModelOperation.SPEECH
                ? 1 : Math.max(1, maxRetries + 1);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String response = timeLimiter.execute(() -> providerAdapter.forward(forwardContext));
                circuitBreaker.recordSuccess(serviceId);
                return response;
            } catch (UpstreamFailureException e) {
                if (!e.isRetryable()) {
                    // 4xx 业务错误不重试、不计熔断健康度，直接透传
                    throw e;
                }
                circuitBreaker.recordFailure(serviceId);
                if (attempt >= maxAttempts) {
                    throw e;
                }
                log.warn("[llm-forward] 上游调用失败，第 {} 次重试: serviceId={}, reason={}",
                        attempt, serviceId, e.getMessage());
            }
        }
        // 理论不可达：循环内每次失败要么重试、要么抛出
        throw new UpstreamFailureException(LlmForwardErrorCodeEnum.FORWARD_FAILED.message(),
                LlmForwardErrorCodeEnum.FORWARD_FAILED, true);
    }

    private RateLimitContext buildRateContext(UserContext ctx, TenantDO tenant, LlmServiceDO service) {
        return RateLimitContext.builder()
                .tenantId(tenant.getTenantId())
                .apiKeyId(ctx.getApiKeyId())
                .serviceId(service.getServiceId())
                .tenantRpm(tenant.getRateLimitRpm())
                .tenantTpm(tenant.getRateLimitTpm())
                .apiKeyRpm(ctx.getApiKeyRateLimitRpm())
                .apiKeyTpm(ctx.getApiKeyRateLimitTpm())
                .modelRpm(service.getRateLimitRpm())
                .modelTpm(service.getRateLimitTpm())
                .build();
    }

    private String settleAndPublish(UserContext ctx, TenantDO tenant, LlmServiceDO service,
                                    RateLimitContext rateCtx, String body, String response,
                                    String requestId, long start, ModelOperation operation) {
        JSONObject responseEnvelope = parseResponseEnvelope(response);
        if (operation == ModelOperation.RERANK && responseEnvelope != null) {
            try {
                JSONArray documents = JSON.parseObject(body).getJSONArray("documents");
                if (documents != null) responseEnvelope.put("verse_rerank_document_count", documents.size());
            } catch (RuntimeException ignored) { /* 请求已由适配器验证。 */ }
        }
        UsageBreakdown normalized = usageNormalizerRegistry.normalize(service.getProvider(), responseEnvelope);
        int totalTokens = safeTokenCount(normalized.totalTokens());
        // 同步结算，保证「拦后续请求」及时生效；token 归属实际服务 serviceId
        settleRateLimitSafely(rateCtx, totalTokens);
        publishTokenUsage(ctx, tenant.getTenantId(), service, responseEnvelope, requestId,
                LlmAuditEvent.STATUS_SUCCESS, start, operation);
        publishAudit(ctx, tenant, service, body, response, requestId, start,
                LlmAuditEvent.STATUS_SUCCESS, null, operation);
        return response;
    }

    /**
     * 投递审计事件：仅当租户开启审计（auditEnabled=1）时记录输入/输出。
     */
    private void publishAudit(UserContext ctx, TenantDO tenant, LlmServiceDO service,
                              String body, String response, String requestId,
                              long start, String status, String errorCode) {
        publishAudit(ctx, tenant, service, body, response, requestId, start,
                status, errorCode, ModelOperation.CHAT_COMPLETIONS);
    }

    private void publishAudit(UserContext ctx, TenantDO tenant, LlmServiceDO service,
                              String body, String response, String requestId,
                              long start, String status, String errorCode, ModelOperation operation) {
        if (!Integer.valueOf(1).equals(tenant.getAuditEnabled())) {
            return;
        }
        LlmAuditEvent event = new LlmAuditEvent();
        event.setRequestId(requestId);
        event.setUserId(ctx.getUserId());
        event.setTenantId(tenant.getTenantId());
        event.setApiKeyId(ctx.getApiKeyId());
        event.setServiceId(service.getServiceId());
        event.setModel(service.getName());
        event.setPrompt(boundedAudit(body, operation));
        event.setResponse(boundedAudit(response, operation));
        event.setLatencyMs((int) (System.currentTimeMillis() - start));
        event.setStatus(status);
        event.setErrorCode(errorCode);
        if (LlmAuditEvent.STATUS_SUCCESS.equals(status)) {
            // Responses 使用 input/output_tokens；统一解析后写入审计，避免丢失其用量。
            UsageBreakdown usage = usageNormalizerRegistry.normalize(service.getProvider(),
                    parseResponseEnvelope(response));
            event.setPromptTokens(usage.inputTokens() == null ? null : safeTokenCount(usage.inputTokens()));
            event.setCompletionTokens(usage.outputTokens() == null ? null : safeTokenCount(usage.outputTokens()));
            event.setTotalTokens(usage.totalTokens() == null ? null : safeTokenCount(usage.totalTokens()));
        }
        publishEventSafely(event);
    }

    private String boundedAudit(String value, ModelOperation operation) {
        if (value == null) return null;
        if (operation == ModelOperation.IMAGE_GENERATION && value.contains("b64_json")) {
            try {
                JSONObject json = JSON.parseObject(value);
                if (json != null && json.getJSONArray("data") != null) {
                    for (Object item : json.getJSONArray("data")) {
                        if (item instanceof JSONObject image && image.containsKey("b64_json")) {
                            image.put("b64_json", "[redacted]");
                        }
                    }
                    value = JSON.toJSONString(json);
                }
            } catch (RuntimeException ignored) { return "[unavailable]"; }
        }
        return value.length() > 16_384 ? value.substring(0, 16_384) : value;
    }

    @Override
    public List<String> listModels(Long tenantId) {
        if (tenantId == null) {
            return List.of();
        }
        return llmServiceMapper.selectList(Wrappers.lambdaQuery(LlmServiceDO.class)
                        .eq(LlmServiceDO::getTenantId, tenantId)
                        .eq(LlmServiceDO::getStatus, 1)
                        .eq(LlmServiceDO::getDelFlag, 0))
                .stream()
                .map(LlmServiceDO::getName)
                .distinct()
                .toList();
    }

    private TenantDO validateTenant(Long tenantId) {
        TenantDO tenant = tenantMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0));
        if (tenant == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        }
        return tenant;
    }

    private void publishTokenUsage(UserContext ctx, Long tenantId, LlmServiceDO service,
                                   JSONObject responseEnvelope, String requestId, String status,
                                   long requestStartedAt) {
        publishTokenUsage(ctx, tenantId, service, responseEnvelope, requestId, status,
                requestStartedAt, ModelOperation.CHAT_COMPLETIONS);
    }

    private void publishTokenUsage(UserContext ctx, Long tenantId, LlmServiceDO service,
                                   JSONObject responseEnvelope, String requestId, String status,
                                   long requestStartedAt, ModelOperation operation) {
        UsageBreakdown evidence = usageNormalizerRegistry.normalize(service.getProvider(), responseEnvelope);
        String usageSource = evidence.valid() ? TokenUsageEvent.SOURCE_EXACT
                : TokenUsageEvent.SOURCE_UNKNOWN;
        publishTokenUsage(ctx, tenantId, service, responseEnvelope, requestId, status, usageSource,
                requestStartedAt, operation);
    }

    /**
     * 流式收尾：结算 TPM + 发布 token/audit 事件，每个流仅触发一次（complete/cancel/error 互斥）。
     */
    private void finalizeStream(UserContext ctx, TenantDO tenant, LlmServiceDO service,
                                RateLimitContext rateCtx, String body, String requestId,
                                long start, StreamResponseAccumulator accumulator,
                                String status, String errorCode) {
        JSONObject responseEnvelope = accumulator.usageEnvelope();
        JSONObject usage = accumulator.usage();
        String response = accumulator.buildResponseJson();
        UsageBreakdown normalized = usageNormalizerRegistry.normalize(service.getProvider(), responseEnvelope);
        int totalTokens = safeTokenCount(normalized.totalTokens());
        // 同步结算 TPM，保证「拦后续请求」及时生效；abort/无 usage 时 totalTokens=0
        settleRateLimitSafely(rateCtx, totalTokens);
        String usageSource = usage == null
                ? TokenUsageEvent.SOURCE_UNKNOWN
                : LlmAuditEvent.STATUS_SUCCESS.equals(status)
                ? TokenUsageEvent.SOURCE_EXACT
                : TokenUsageEvent.SOURCE_ESTIMATED;
        publishTokenUsage(ctx, tenant.getTenantId(), service, responseEnvelope, requestId, status, usageSource, start);
        publishAuditStream(ctx, tenant, service, body, response, requestId, start, usage, status, errorCode);
    }

    private void finalizeStreamOnce(AtomicBoolean finalized, UserContext ctx, TenantDO tenant,
                                    LlmServiceDO service, RateLimitContext rateCtx, String body,
                                    String requestId, long start, StreamResponseAccumulator accumulator,
                                    String status, String errorCode) {
        if (finalized.compareAndSet(false, true)) {
            finalizeStream(ctx, tenant, service, rateCtx, body, requestId, start, accumulator, status, errorCode);
        }
    }

    private void publishTokenUsage(UserContext ctx, Long tenantId, LlmServiceDO service,
                                   JSONObject responseEnvelope, String requestId, String status,
                                   String usageSource, long requestStartedAt) {
        publishTokenUsage(ctx, tenantId, service, responseEnvelope, requestId, status, usageSource,
                requestStartedAt, ModelOperation.CHAT_COMPLETIONS);
    }

    private void publishTokenUsage(UserContext ctx, Long tenantId, LlmServiceDO service,
                                   JSONObject responseEnvelope, String requestId, String status,
                                   String usageSource, long requestStartedAt, ModelOperation operation) {
        if (!costingEnabled) {
            return;
        }
        TokenUsageEvent event = new TokenUsageEvent();
        event.setUserId(ctx.getUserId());
        event.setTenantId(tenantId);
        event.setApiKeyId(ctx.getApiKeyId());
        event.setServiceId(service.getServiceId());
        event.setModel(service.getName());
        event.setOperation(operation.name());
        event.setRequestId(requestId);
        event.setStatus(status);
        event.setUsageSource(usageSource);
        Instant started = Instant.ofEpochMilli(requestStartedAt);
        event.setRequestStartedAt(started);
        try {
            UsageBreakdown normalized = usageNormalizerRegistry.normalize(service.getProvider(), responseEnvelope);
            event.setNormalizedUsage(normalized);
            // 缺失 Token 证据必须保持空值，不能写成精确零消耗。
            event.setPromptTokens(normalized.inputTokens() == null ? null : safeTokenCount(normalized.inputTokens()));
            event.setCompletionTokens(normalized.outputTokens() == null ? null : safeTokenCount(normalized.outputTokens()));
            event.setTotalTokens(normalized.totalTokens() == null ? null : safeTokenCount(normalized.totalTokens()));
            if (operation == ModelOperation.IMAGE_GENERATION && responseEnvelope != null
                    && responseEnvelope.getJSONArray("data") != null) {
                event.setImageCount(responseEnvelope.getJSONArray("data").size());
            }
            if (operation == ModelOperation.RERANK && responseEnvelope != null
                    && responseEnvelope.getInteger("verse_rerank_document_count") != null) {
                event.setRerankDocumentCount(responseEnvelope.getInteger("verse_rerank_document_count"));
            }
            if (operation == ModelOperation.TRANSCRIPTION && responseEnvelope != null) {
                event.setAudioDurationMs(responseEnvelope.getLong("verse_audio_duration_ms"));
            }
            event.setUsageDetailsJson(normalized.rawUsage() == null
                    ? null : JSON.toJSONString(normalized.rawUsage()));
            if (LlmAuditEvent.STATUS_SUCCESS.equals(status)) {
                PricingSnapshot pricing = pricingResolver.resolve(tenantId, service.getServiceId(), started);
                event.setPricingSnapshot(pricing);
                event.setCostResult(costCalculator.calculate(status, normalized, pricing));
            } else {
                event.setCostResult(CostResult.of(CostStatus.NOT_CHARGEABLE));
            }
        } catch (Exception e) {
            // 计费属于旁路能力，失败时记录为不可计算，不能覆盖已经取得的上游响应。
            log.warn("[llm-cost] 计算费用失败: tenantId={}, serviceId={}, requestId={}",
                    tenantId, service.getServiceId(), requestId, e);
            event.setCostResult(CostResult.of(CostStatus.UNCALCULABLE));
        }
        // Outbox 写入成功才代表计费用量事件已被可靠接管；失败必须显式向上传播。
        tokenUsageEventPublisher.publish(event);
    }

    private void publishAuditStream(UserContext ctx, TenantDO tenant, LlmServiceDO service,
                                    String body, String response, String requestId, long start,
                                    JSONObject usage, String status, String errorCode) {
        if (!Integer.valueOf(1).equals(tenant.getAuditEnabled())) {
            return;
        }
        LlmAuditEvent event = new LlmAuditEvent();
        event.setRequestId(requestId);
        event.setUserId(ctx.getUserId());
        event.setTenantId(tenant.getTenantId());
        event.setApiKeyId(ctx.getApiKeyId());
        event.setServiceId(service.getServiceId());
        event.setModel(service.getName());
        event.setPrompt(body);
        event.setResponse(response);
        event.setLatencyMs((int) (System.currentTimeMillis() - start));
        event.setStatus(status);
        event.setErrorCode(errorCode);
        event.setPromptTokens(getInt(usage, "prompt_tokens"));
        event.setCompletionTokens(getInt(usage, "completion_tokens"));
        event.setTotalTokens(getInt(usage, "total_tokens"));
        publishEventSafely(event);
    }

    private void settleRateLimitSafely(RateLimitContext rateCtx, int totalTokens) {
        try {
            rateLimiter.settle(rateCtx, totalTokens);
        } catch (Exception e) {
            // 响应已经由上游成功返回，结算异常只记录告警，避免将成功请求改写为网关失败。
            log.warn("[llm-forward] Token 限流结算失败: tenantId={}, serviceId={}",
                    rateCtx.getTenantId(), rateCtx.getServiceId(), e);
        }
    }

    private void publishEventSafely(DomainEvent event) {
        try {
            eventPublisher.publish(event);
        } catch (Exception e) {
            // 审计和用量消息均为旁路持久化，投递异常不得影响主转发链路。
            log.warn("[llm-forward] 领域事件投递失败: eventType={}, eventId={}",
                    event.eventType(), event.getEventId(), e);
        }
    }

    private String errorCode(Throwable e) {
        if (e instanceof UpstreamFailureException ufe) {
            return ufe.getErrorCode();
        }
        return LlmForwardErrorCodeEnum.FORWARD_FAILED.code();
    }

    private JSONObject parseResponseEnvelope(String response) {
        if (!StringUtils.hasText(response)) {
            return null;
        }
        try {
            return JSON.parseObject(response);
        } catch (Exception e) {
            log.debug("[llm-forward] 解析响应 envelope 失败: {}", e.getMessage());
            return null;
        }
    }

    private JSONObject usageObject(JSONObject responseEnvelope) {
        if (responseEnvelope == null) {
            return null;
        }
        Object usage = responseEnvelope.get("usage");
        if (usage instanceof JSONObject json) {
            return json;
        }
        Object metadata = responseEnvelope.get("usageMetadata");
        return metadata instanceof JSONObject json ? json : null;
    }

    private int safeTokenCount(Long value) {
        if (value == null || value <= 0) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value.intValue();
    }

    private int getInt(JSONObject obj, String key) {
        if (obj == null) {
            return 0;
        }
        try {
            Integer value = obj.getInteger(key);
            return value == null ? 0 : value;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
