package com.yonagi.verse.async.handler;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEventHandler;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.dao.entity.TokenUsageDO;
import com.yonagi.verse.dao.entity.TokenUsageCostDO;
import com.yonagi.verse.dao.mapper.TokenUsageCostMapper;
import com.yonagi.verse.dao.mapper.TokenUsageMapper;
import com.yonagi.verse.common.enums.CostStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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
@Slf4j
public class TokenUsageEventHandler implements DomainEventHandler<TokenUsageEvent> {

    private final TokenUsageMapper tokenUsageMapper;
    private final TokenUsageCostMapper tokenUsageCostMapper;

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
        validate(event);
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
            tokenUsage.setNormalizedTotalTokens(event.getNormalizedUsage().totalTokens());
            tokenUsage.setUsageParser(event.getNormalizedUsage().parser());
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
            tokenUsage.setPriceEffectiveFrom(toShanghai(event.getPricingSnapshot().effectiveFrom()));
            tokenUsage.setPriceEffectiveTo(toShanghai(event.getPricingSnapshot().effectiveTo()));
        }
        tokenUsage.setEstimatedCostFen(event.getCostResult() == null
                ? null
                : event.getCostResult().estimatedCostFen());
        tokenUsage.setCostStatus(event.getCostResult() == null
                ? CostStatus.UNPRICED.name()
                : event.getCostResult().status().name());
        tokenUsage.setUsageDetailsJson(event.getUsageDetailsJson());
        try {
            tokenUsageMapper.insert(tokenUsage);
            if (tokenUsageCostMapper != null) tokenUsageCostMapper.insert(toCostFact(tokenUsage.getId(), tokenUsage));
        } catch (DuplicateKeyException e) {
            if (tokenUsageCostMapper == null) {
                if (tokenUsageMapper.countByEventId(event.getEventId()) > 0) {
                    log.info("[token-usage] 重复事件已确认: eventId={}", event.getEventId());
                    return;
                }
                throw e;
            }
            Long usageId = tokenUsageMapper.selectIdByEventId(event.getEventId());
            if (usageId != null && tokenUsageCostMapper.countByUsageId(usageId) == 1) {
                log.info("[token-usage] 重复事件已确认: eventId={}", event.getEventId());
                return;
            }
            if (usageId != null) {
                throw new IllegalStateException("用量事实缺少费用事实: eventId=" + event.getEventId(), e);
            }
            throw e;
        }
    }

    /** 从兼容期用量对象复制不可变费用快照。 */
    private TokenUsageCostDO toCostFact(Long usageId, TokenUsageDO usage) {
        TokenUsageCostDO cost = new TokenUsageCostDO();
        cost.setUsageId(usageId);
        cost.setPricingId(usage.getPricingId());
        cost.setBillingMode(usage.getBillingMode());
        cost.setPricePeriodType(usage.getPricePeriodType());
        cost.setPricePeriodId(usage.getPricePeriodId());
        cost.setPriceEffectiveFrom(usage.getPriceEffectiveFrom());
        cost.setPriceEffectiveTo(usage.getPriceEffectiveTo());
        cost.setCacheMissInputPriceFen(usage.getCacheMissInputPriceFen());
        cost.setCacheHitInputPriceFen(usage.getCacheHitInputPriceFen());
        cost.setOutputPriceFen(usage.getOutputPriceFen());
        cost.setRequestPriceFen(usage.getRequestPriceFen());
        cost.setEstimatedCostFen(usage.getEstimatedCostFen());
        cost.setCostStatus(usage.getCostStatus());
        cost.setCurrency(usage.getCurrency());
        cost.setCreateTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        return cost;
    }

    private void validate(TokenUsageEvent event) {
        if (event == null || event.getEventId() == null || event.getEventId().isBlank()
                || event.getTenantId() == null || event.getUserId() == null
                || event.getApiKeyId() == null || event.getServiceId() == null
                || event.getModel() == null || event.getModel().isBlank()
                || event.getStatus() == null || event.getUsageSource() == null
                || event.getRequestStartedAt() == null) {
            throw new IllegalArgumentException("计费用量事件缺少必要字段");
        }
        if (event.getCostResult() == null || event.getCostResult().status() == null) {
            throw new IllegalArgumentException("计费用量事件缺少费用终态");
        }
        boolean calculated = CostStatus.CALCULATED == event.getCostResult().status();
        if (calculated != (event.getCostResult().estimatedCostFen() != null)) {
            throw new IllegalArgumentException("费用金额与费用终态不一致");
        }
    }

    private LocalDateTime toShanghai(java.time.Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Shanghai"));
    }
}
