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
import com.yonagi.verse.service.LlmForwardService;
import com.yonagi.verse.service.forward.ForwardContext;
import com.yonagi.verse.service.forward.ModelResolver;
import com.yonagi.verse.service.forward.ProviderAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * LLM 转发服务实现 — 非事务同步阻塞编排：解析模型 → 解密上游 key → 适配器转发 → 解析 usage → 发 Token 事件。
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

    @Override
    public String chatCompletion(UserContext ctx, String body, String requestId) {
        if (ctx == null || ctx.getCurrentTenantId() == null) {
            throw new ClientException(LlmForwardErrorCodeEnum.API_KEY_INVALID);
        }
        Long tenantId = ctx.getCurrentTenantId();
        validateTenant(tenantId);

        JSONObject bodyJson = JSON.parseObject(body);
        if (bodyJson == null) {
            throw new ClientException(LlmForwardErrorCodeEnum.MODEL_NOT_FOUND);
        }
        String model = bodyJson.getString("model");
        if (!StringUtils.hasText(model)) {
            throw new ClientException(LlmForwardErrorCodeEnum.MODEL_NOT_FOUND);
        }

        LlmServiceDO service = modelResolver.resolve(tenantId, model);
        String realApiKey = aesUtil.decrypt(service.getApiKey());

        ForwardContext forwardContext = ForwardContext.builder()
                .apiUrl(service.getApiUrl())
                .apiKey(realApiKey)
                .modelName(service.getModelName())
                .body(body)
                .build();
        String response = providerAdapter.forward(forwardContext);

        publishTokenUsage(ctx, tenantId, service, model, response, requestId);
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

    private void validateTenant(Long tenantId) {
        TenantDO tenant = tenantMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0));
        if (tenant == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        }
    }

    private void publishTokenUsage(UserContext ctx, Long tenantId, LlmServiceDO service,
                                   String model, String response, String requestId) {
        JSONObject usage = extractUsage(response);
        TokenUsageEvent event = new TokenUsageEvent();
        event.setUserId(ctx.getUserId());
        event.setTenantId(tenantId);
        event.setApiKeyId(ctx.getApiKeyId());
        event.setServiceId(service.getServiceId());
        event.setModel(model);
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
