package com.yonagi.verse.async.handler;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEventHandler;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.dao.entity.TokenUsageDO;
import com.yonagi.verse.dao.mapper.TokenUsageMapper;
import com.yonagi.verse.common.enums.CostStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Token 消耗事件消费者 — 落 t_token_usage（createTime 手动填充，不依赖 MetaObjectHandler）。
 *
 * @author Yonagi
 */
@Component
@RequiredArgsConstructor
public class TokenUsageEventHandler implements DomainEventHandler<TokenUsageEvent> {

    private final TokenUsageMapper tokenUsageMapper;

    @Override
    public String eventType() {
        return EventTag.TOKEN_USAGE;
    }

    @Override
    public Class<TokenUsageEvent> eventClass() {
        return TokenUsageEvent.class;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onEvent(TokenUsageEvent event) {
        TokenUsageDO tokenUsage = new TokenUsageDO();
        tokenUsage.setUserId(event.getUserId());
        tokenUsage.setTenantId(event.getTenantId());
        tokenUsage.setApiKeyId(event.getApiKeyId());
        tokenUsage.setServiceId(event.getServiceId());
        tokenUsage.setModel(event.getModel());
        tokenUsage.setPromptTokens(event.getPromptTokens());
        tokenUsage.setCompletionTokens(event.getCompletionTokens());
        tokenUsage.setTotalTokens(event.getTotalTokens());
        tokenUsage.setRequestId(event.getRequestId());
        tokenUsage.setStatus(event.getStatus());
        tokenUsage.setUsageSource(event.getUsageSource());
        tokenUsage.setEventId(event.getEventId());
        tokenUsage.setRequestStartedAt(event.getRequestStartedAt() == null
                ? null
                : LocalDateTime.ofInstant(event.getRequestStartedAt(), ZoneId.of("Asia/Shanghai")));
        if (event.getNormalizedUsage() != null) {
            tokenUsage.setInputTokens(event.getNormalizedUsage().inputTokens());
            tokenUsage.setCachedInputTokens(event.getNormalizedUsage().cachedInputTokens());
            tokenUsage.setCacheWriteInputTokens(event.getNormalizedUsage().cacheWriteInputTokens());
            tokenUsage.setOutputTokens(event.getNormalizedUsage().outputTokens());
        }
        if (event.getPricingSnapshot() != null) {
            tokenUsage.setPricingId(event.getPricingSnapshot().pricingId());
            tokenUsage.setBillingMode(event.getPricingSnapshot().billingMode() == null
                    ? null
                    : event.getPricingSnapshot().billingMode().name());
            tokenUsage.setPricePeriodType(event.getPricingSnapshot().periodType() == null
                    ? null
                    : event.getPricingSnapshot().periodType().name());
            tokenUsage.setPricePeriodId(event.getPricingSnapshot().periodId());
            tokenUsage.setCacheMissInputPriceFen(event.getPricingSnapshot().cacheMissInputPriceFen());
            tokenUsage.setCacheHitInputPriceFen(event.getPricingSnapshot().cacheHitInputPriceFen());
            tokenUsage.setOutputPriceFen(event.getPricingSnapshot().outputPriceFen());
            tokenUsage.setRequestPriceFen(event.getPricingSnapshot().requestPriceFen());
            tokenUsage.setCurrency(event.getPricingSnapshot().currency());
        }
        tokenUsage.setEstimatedCostFen(event.getCostResult() == null
                ? null
                : event.getCostResult().estimatedCostFen());
        tokenUsage.setCostStatus(event.getCostResult() == null
                ? CostStatus.UNPRICED.name()
                : event.getCostResult().status().name());
        tokenUsage.setUsageDetailsJson(event.getUsageDetailsJson());
        tokenUsageMapper.insertIdempotently(tokenUsage);
    }
}
