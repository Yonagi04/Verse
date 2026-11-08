package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.dao.entity.CostReconciliationAttemptDO;
import com.yonagi.verse.service.forward.UpstreamExecutionOutcome;
import com.yonagi.verse.service.pricing.CostCalculator;
import com.yonagi.verse.service.pricing.CostResult;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import com.yonagi.verse.service.usage.UsageBreakdown;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** 费用依据来自同一次物理尝试和该尝试固化的历史价格。 */
@Component
@RequiredArgsConstructor
public class ReconciliationEvidence {
    public static final long MAX_TOKENS = 999_999_999_999L;
    private static final Set<String> VERIFIED_OPENAI_PROVIDERS = Set.of("openai", "azure", "siliconflow");
    private final CostCalculator calculator;

    public record Tokens(Long input, Long cached, Long output) {
        public boolean valid() {
            return validCount(input) && validCount(cached) && validCount(output) && cached <= input;
        }

        private static boolean validCount(Long value) {
            return value != null && value >= 0 && value <= MAX_TOKENS;
        }

        UsageBreakdown breakdown() {
            return new UsageBreakdown(input, cached, null, output, null,
                    TokenUsageEvent.SOURCE_EXACT, "reconciliation", null, valid());
        }
    }

    public PricingSnapshot price(CostReconciliationAttemptDO attempt) {
        return JSON.parseObject(attempt.getPricingJson(), PricingSnapshot.class);
    }

    public CostResult charge(PricingSnapshot price, Tokens usage) {
        return calculator.calculateCharge(usage == null ? null : usage.breakdown(), price);
    }

    public CostResult automatic(CostReconciliationAttemptDO attempt) {
        if (attempt == null) {
            return CostResult.of(CostStatus.UNCALCULABLE);
        }
        if ("NOT_SENT".equals(attempt.getOutcome())) {
            return CostResult.of(CostStatus.NOT_CHARGEABLE);
        }
        PricingSnapshot price = price(attempt);
        if (!price.priced()) {
            return CostResult.of(CostStatus.UNPRICED);
        }
        if (!"COMPLETED".equals(attempt.getOutcome())) {
            return CostResult.of(CostStatus.UNCALCULABLE);
        }
        return charge(price, JSON.parseObject(attempt.getUsageJson(), Tokens.class));
    }

    void recordTerminal(CostReconciliationAttemptDO attempt, TokenUsageEvent event) {
        recordTerminal(attempt, event, false);
    }

    void recordTerminal(CostReconciliationAttemptDO attempt, TokenUsageEvent event, boolean verifiedTransport) {
        var outcome = event.getExecutionOutcome();
        if (outcome != UpstreamExecutionOutcome.COMPLETED) {
            return;
        }
        var price = price(attempt);
        var usage = exactTokens(event, price);
        if (price.billingMode() == BillingMode.REQUEST && !"COMPLETED".equals(attempt.getOutcome())
                && (!verifiedTransport || !supportsFixedEvidence(attempt))) {
            return;
        }
        // 失败响应的协议终态不足以确认按次收费。已完成且有权威 Token 用量可以独立计费。
        if (!"SUCCESS".equals(event.getStatus()) && (price.billingMode() != BillingMode.TOKEN || usage == null)) {
            return;
        }
        attempt.setOutcome("COMPLETED");
        if (usage != null) {
            attempt.setUsageJson(JSON.toJSONString(usage));
        }
    }

    private boolean supportsFixedEvidence(CostReconciliationAttemptDO attempt) {
        String provider = attempt.getProvider().toLowerCase(Locale.ROOT);
        return VERIFIED_OPENAI_PROVIDERS.contains(provider) && switch (attempt.getOperation()) {
            case "CHAT_COMPLETIONS", "RESPONSES", "EMBEDDINGS", "RERANK",
                 "IMAGE_GENERATION", "TRANSCRIPTION", "SPEECH" -> true;
            default -> false;
        };
    }

    private Tokens exactTokens(TokenUsageEvent event, PricingSnapshot price) {
        var usage = event.getNormalizedUsage();
        if (!TokenUsageEvent.SOURCE_EXACT.equals(event.getUsageSource()) || usage == null || !usage.valid()) {
            return null;
        }
        var tokens = new Tokens(usage.inputTokens(), usage.cachedInputTokens(), usage.outputTokens());
        if (!tokens.valid()) {
            return null;
        }
        if (price.billingMode() == BillingMode.TOKEN && price.cacheHitInputPriceFen() != null
                && price.cacheMissInputPriceFen() != null
                && price.cacheHitInputPriceFen().compareTo(price.cacheMissInputPriceFen()) != 0
                && !hasReportedCachedTokens(usage.rawUsage())) {
            return null;
        }
        return tokens;
    }

    private boolean hasReportedCachedTokens(JSONObject raw) {
        if (raw == null) {
            return false;
        }
        return raw.get("cached_tokens") != null || raw.get("cache_read_input_tokens") != null
                || raw.get("cachedContentTokenCount") != null || raw.get("cache_tokens") != null
                || raw.get("prompt_cache_hit_tokens") != null
                || reportedDetails(raw.get("prompt_tokens_details")) || reportedDetails(raw.get("input_tokens_details"));
    }

    private boolean reportedDetails(Object details) {
        return details instanceof JSONObject fields && fields.get("cached_tokens") != null;
    }
}
