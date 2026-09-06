package com.yonagi.verse.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.async.api.DomainEvent;
import com.yonagi.verse.async.event.LlmAuditEvent;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
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
        RateLimitContext primaryCtx = buildRateContext(ctx, tenant, primary);

        LlmServiceDO actualService = primary;
        RateLimitContext actualCtx = primaryCtx;
        String response;
        long start = requestStartedAt.toEpochMilli();
        try {
            response = forwardWithResilience(primary, primaryCtx, body);
        } catch (UpstreamFailureException e) {
            if (!e.isRetryable()) {
                publishTokenUsage(
                        ctx, tenant.getTenantId(), primary, null,
                        requestId, LlmAuditEvent.STATUS_FAIL, start
                );
                publishAudit(ctx, tenant, primary, body, null, requestId, start,
                        LlmAuditEvent.STATUS_FAIL, e.getErrorCode());
                throw e;
            }
            // 主模型可重试失败/熔断打开 → 降级到备用模型
            LlmServiceDO fallbackService = fallbackExecutor.resolveFallback(primary);
            if (fallbackService == null) {
                // 无备用模型：透传主模型真实错误（含上游错误信息/熔断状态），不误报为「模型已熔断」
                publishTokenUsage(
                        ctx, tenant.getTenantId(), primary, null,
                        requestId, LlmAuditEvent.STATUS_FAIL, start
                );
                publishAudit(ctx, tenant, primary, body, null, requestId, start,
                        LlmAuditEvent.STATUS_FAIL, e.getErrorCode());
                throw e;
            }
            log.warn("[llm-forward] 主模型转发失败，降级到备用模型: primary={}, fallback={}, reason={}",
                    primary.getServiceId(), fallbackService.getServiceId(), e.getMessage());
            actualService = fallbackService;
            actualCtx = buildRateContext(ctx, tenant, fallbackService);
            try {
                // 备用模型走完整韧性流程（限流 + 熔断 + 转发）
                response = forwardWithResilience(fallbackService, actualCtx, body);
            } catch (UpstreamFailureException e2) {
                // 备用模型也失败：透传备用模型真实错误
                log.warn("[llm-forward] 备用模型转发失败: fallback={}, reason={}",
                        fallbackService.getServiceId(), e2.getMessage());
                publishTokenUsage(
                        ctx, tenant.getTenantId(), fallbackService, null,
                        requestId, LlmAuditEvent.STATUS_FAIL, start
                );
                publishAudit(ctx, tenant, fallbackService, body, null, requestId, start,
                        LlmAuditEvent.STATUS_FAIL, e2.getErrorCode());
                throw e2;
            }
        }

        return settleAndPublish(ctx, tenant, actualService, actualCtx, body, response, requestId, start);
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
        RateLimitContext rateCtx = buildRateContext(ctx, tenant, service);
        rateLimiter.check(rateCtx);

        String serviceId = String.valueOf(service.getServiceId());
        if (circuitBreaker.isOpen(serviceId)) {
            throw new UpstreamFailureException(LlmForwardErrorCodeEnum.MODEL_CIRCUIT_OPEN.message(),
                    LlmForwardErrorCodeEnum.MODEL_CIRCUIT_OPEN, true);
        }

        String realApiKey = aesUtil.decrypt(service.getApiKey());
        ForwardContext forwardContext = ForwardContext.builder()
                .apiUrl(service.getApiUrl())
                .apiKey(realApiKey)
                .modelName(service.getModelName())
                .body(body)
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

    /**
     * 单次转发 + 韧性包裹：限流检查 → 熔断判断 → 上游调用（超时 + 短重试）→ 记录成功/失败。
     */
    private String forwardWithResilience(LlmServiceDO service, RateLimitContext rateCtx, String body) {
        rateLimiter.check(rateCtx);

        String serviceId = String.valueOf(service.getServiceId());
        if (circuitBreaker.isOpen(serviceId)) {
            throw new UpstreamFailureException(LlmForwardErrorCodeEnum.MODEL_CIRCUIT_OPEN.message(),
                    LlmForwardErrorCodeEnum.MODEL_CIRCUIT_OPEN, true);
        }

        String realApiKey = aesUtil.decrypt(service.getApiKey());
        ForwardContext forwardContext = ForwardContext.builder()
                .apiUrl(service.getApiUrl())
                .apiKey(realApiKey)
                .modelName(service.getModelName())
                .body(body)
                .build();

        int maxAttempts = Math.max(1, maxRetries + 1);
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
                                    String requestId, long start) {
        JSONObject usage = extractUsage(response);
        int totalTokens = getInt(usage, "total_tokens");
        // 同步结算，保证「拦后续请求」及时生效；token 归属实际服务 serviceId
        settleRateLimitSafely(rateCtx, totalTokens);
        publishTokenUsage(ctx, tenant.getTenantId(), service, usage, requestId, LlmAuditEvent.STATUS_SUCCESS, start);
        publishAudit(ctx, tenant, service, body, response, requestId, start,
                LlmAuditEvent.STATUS_SUCCESS, null);
        return response;
    }

    /**
     * 投递审计事件：仅当租户开启审计（auditEnabled=1）时记录输入/输出。
     */
    private void publishAudit(UserContext ctx, TenantDO tenant, LlmServiceDO service,
                              String body, String response, String requestId,
                              long start, String status, String errorCode) {
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
        if (LlmAuditEvent.STATUS_SUCCESS.equals(status)) {
            JSONObject usage = extractUsage(response);
            event.setPromptTokens(getInt(usage, "prompt_tokens"));
            event.setCompletionTokens(getInt(usage, "completion_tokens"));
            event.setTotalTokens(getInt(usage, "total_tokens"));
        }
        publishEventSafely(event);
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
                                   JSONObject usage, String requestId, String status, long requestStartedAt) {
        String usageSource = usage == null ? TokenUsageEvent.SOURCE_UNKNOWN : TokenUsageEvent.SOURCE_EXACT;
        publishTokenUsage(ctx, tenantId, service, usage, requestId, status, usageSource, requestStartedAt);
    }

    /**
     * 流式收尾：结算 TPM + 发布 token/audit 事件，每个流仅触发一次（complete/cancel/error 互斥）。
     */
    private void finalizeStream(UserContext ctx, TenantDO tenant, LlmServiceDO service,
                                RateLimitContext rateCtx, String body, String requestId,
                                long start, StreamResponseAccumulator accumulator,
                                String status, String errorCode) {
        JSONObject usage = accumulator.usage();
        String response = accumulator.buildResponseJson();
        int totalTokens = getInt(usage, "total_tokens");
        // 同步结算 TPM，保证「拦后续请求」及时生效；abort/无 usage 时 totalTokens=0
        settleRateLimitSafely(rateCtx, totalTokens);
        String usageSource = usage == null
                ? TokenUsageEvent.SOURCE_UNKNOWN
                : LlmAuditEvent.STATUS_SUCCESS.equals(status)
                ? TokenUsageEvent.SOURCE_EXACT
                : TokenUsageEvent.SOURCE_ESTIMATED;
        publishTokenUsage(ctx, tenant.getTenantId(), service, usage, requestId, status, usageSource, start);
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
                                   JSONObject usage, String requestId, String status,
                                   String usageSource, long requestStartedAt) {
        if (!costingEnabled) {
            return;
        }
        TokenUsageEvent event = new TokenUsageEvent();
        event.setUserId(ctx.getUserId());
        event.setTenantId(tenantId);
        event.setApiKeyId(ctx.getApiKeyId());
        event.setServiceId(service.getServiceId());
        event.setModel(service.getName());
        event.setPromptTokens(getInt(usage, "prompt_tokens"));
        event.setCompletionTokens(getInt(usage, "completion_tokens"));
        event.setTotalTokens(getInt(usage, "total_tokens"));
        event.setRequestId(requestId);
        event.setStatus(status);
        event.setUsageSource(usageSource);
        Instant started = Instant.ofEpochMilli(requestStartedAt);
        event.setRequestStartedAt(started);
        try {
            JSONObject response = new JSONObject();
            response.put("usage", usage);
            UsageBreakdown normalized = usageNormalizerRegistry.normalize(service.getProvider(), response);
            event.setNormalizedUsage(normalized);
            if (LlmAuditEvent.STATUS_SUCCESS.equals(status)) {
                PricingSnapshot pricing = pricingResolver.resolve(tenantId, service.getServiceId(), started);
                event.setPricingSnapshot(pricing);
                event.setCostResult(costCalculator.calculate(status, normalized, pricing));
            } else {
                event.setCostResult(CostResult.of(CostStatus.NOT_CHARGEABLE));
            }
            event.setUsageDetailsJson(usage == null ? null : JSON.toJSONString(usage));
        } catch (Exception e) {
            // 计费属于旁路能力，失败时记录为不可计算，不能覆盖已经取得的上游响应。
            log.warn("[llm-cost] 计算费用失败: tenantId={}, serviceId={}, requestId={}",
                    tenantId, service.getServiceId(), requestId, e);
            event.setCostResult(CostResult.of(CostStatus.UNCALCULABLE));
        }
        publishEventSafely(event);
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

    private JSONObject extractUsage(String response) {
        if (!StringUtils.hasText(response)) {
            return null;
        }
        try {
            JSONObject json = JSON.parseObject(response);
            return json == null ? null : json.getJSONObject("usage");
        } catch (Exception e) {
            log.debug("[llm-forward] 解析 usage 失败: {}", e.getMessage());
            return null;
        }
    }

    private int getInt(JSONObject obj, String key) {
        if (obj == null) {
            return 0;
        }
        Integer value = obj.getInteger(key);
        return value == null ? 0 : value;
    }
}
