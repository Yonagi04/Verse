package com.yonagi.verse.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.common.convention.exception.ClientException;
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
import com.yonagi.verse.service.LlmForwardService;
import com.yonagi.verse.service.forward.ForwardContext;
import com.yonagi.verse.service.forward.ModelResolver;
import com.yonagi.verse.service.forward.ProviderAdapter;
import com.yonagi.verse.service.forward.UpstreamFailureException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

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

    @Override
    public String chatCompletion(UserContext ctx, String body, String requestId) {
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
        try {
            response = forwardWithResilience(primary, primaryCtx, body);
        } catch (UpstreamFailureException e) {
            if (!e.isRetryable()) {
                throw e;
            }
            // 主模型可重试失败/熔断打开 → 降级到备用模型
            LlmServiceDO fallbackService = fallbackExecutor.resolveFallback(primary);
            if (fallbackService == null) {
                // 无备用模型：透传主模型真实错误（含上游错误信息/熔断状态），不误报为「模型已熔断」
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
                throw e2;
            }
        }

        return settleAndPublish(ctx, tenantId, actualService, actualCtx, response, requestId);
    }

    /**
     * 单次转发 + 韧性包裹：限流检查 → 熔断判断 → 上游调用 → 记录成功/失败。
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
        try {
            String response = providerAdapter.forward(forwardContext);
            circuitBreaker.recordSuccess(serviceId);
            return response;
        } catch (UpstreamFailureException e) {
            if (e.isRetryable()) {
                circuitBreaker.recordFailure(serviceId);
            }
            throw e;
        }
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

    private String settleAndPublish(UserContext ctx, Long tenantId, LlmServiceDO service,
                                    RateLimitContext rateCtx, String response, String requestId) {
        JSONObject usage = extractUsage(response);
        int totalTokens = getInt(usage, "total_tokens");
        // 同步结算，保证「拦后续请求」及时生效；token 归属实际服务 serviceId
        rateLimiter.settle(rateCtx, totalTokens);
        publishTokenUsage(ctx, tenantId, service, usage, requestId);
        return response;
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
                                   JSONObject usage, String requestId) {
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
        eventPublisher.publish(event);
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
