package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.dao.entity.CostReconciliationAttemptDO;
import com.yonagi.verse.service.forward.UpstreamExecutionOutcome;
import com.yonagi.verse.service.pricing.CostCalculator;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import com.yonagi.verse.service.usage.UsageBreakdown;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationEvidenceTest {
    private final ReconciliationEvidence evidence = new ReconciliationEvidence(new CostCalculator());

    @Test
    void fixedPriceNeedsVerifiedTerminalAndUsesOriginalPrice() {
        var attempt = attempt(BillingMode.REQUEST, "openai");
        var event = terminal(UpstreamExecutionOutcome.COMPLETED, null);
        evidence.recordTerminal(attempt, event);
        assertThat(evidence.automatic(attempt).status()).isEqualTo(CostStatus.UNCALCULABLE);

        evidence.recordTerminal(attempt, event, true);
        assertThat(evidence.automatic(attempt).estimatedCostFen()).isEqualByComparingTo("600");
    }

    @Test
    void genericRejectionAndUnknownProviderCannotConfirmFixedCost() {
        var attempt = attempt(BillingMode.REQUEST, "custom");
        evidence.recordTerminal(attempt, terminal(UpstreamExecutionOutcome.REJECTED, null));
        assertThat(evidence.automatic(attempt).status()).isEqualTo(CostStatus.UNCALCULABLE);
        evidence.recordTerminal(attempt, terminal(UpstreamExecutionOutcome.COMPLETED, null), true);
        assertThat(evidence.automatic(attempt).status()).isEqualTo(CostStatus.UNCALCULABLE);
        attempt.setOutcome("NOT_SENT");
        assertThat(evidence.automatic(attempt).status()).isEqualTo(CostStatus.NOT_CHARGEABLE);
    }

    @Test
    void tokenNormalizerDefaultZeroCannotSubstituteForMissingPricedCacheEvidence() {
        var attempt = attempt(BillingMode.TOKEN, "openai");
        var usage = new UsageBreakdown(1_000_000L, 0L, null, 1_000_000L, null,
                TokenUsageEvent.SOURCE_EXACT, "fixture", new JSONObject(), true);
        var event = terminal(UpstreamExecutionOutcome.COMPLETED, usage);
        evidence.recordTerminal(attempt, event);
        assertThat(evidence.automatic(attempt).status()).isEqualTo(CostStatus.UNCALCULABLE);

        usage.rawUsage().put("cached_tokens", 0);
        evidence.recordTerminal(attempt, event);
        assertThat(evidence.automatic(attempt).estimatedCostFen()).isEqualByComparingTo("1000");
    }

    @Test
    void estimatedStreamTokensRemainUnconfirmed() {
        var attempt = attempt(BillingMode.TOKEN, "openai");
        var usage = new UsageBreakdown(100L, 0L, null, 20L, null,
                TokenUsageEvent.SOURCE_ESTIMATED, "fixture", JSONObject.of("cached_tokens", 0), true);
        var event = terminal(UpstreamExecutionOutcome.UNKNOWN, usage);
        event.setUsageSource(TokenUsageEvent.SOURCE_ESTIMATED);
        evidence.recordTerminal(attempt, event);
        assertThat(evidence.automatic(attempt).status()).isEqualTo(CostStatus.UNCALCULABLE);
        assertThat(new ReconciliationEvidence.Tokens(20L, 21L, 0L).valid()).isFalse();
    }

    private TokenUsageEvent terminal(UpstreamExecutionOutcome outcome, UsageBreakdown usage) {
        var event = new TokenUsageEvent();
        event.setStatus("SUCCESS");
        event.setExecutionOutcome(outcome);
        event.setUsageSource(TokenUsageEvent.SOURCE_EXACT);
        event.setNormalizedUsage(usage);
        return event;
    }

    private CostReconciliationAttemptDO attempt(BillingMode mode, String provider) {
        var row = new CostReconciliationAttemptDO();
        row.setProvider(provider);
        row.setOperation("CHAT_COMPLETIONS");
        row.setOutcome("INTENT");
        row.setPricingJson(JSON.toJSONString(new PricingSnapshot(1L, mode, "CNY", null, null,
                new BigDecimal("200"), new BigDecimal("50"), new BigDecimal("800"), new BigDecimal("600"), null, null)));
        return row;
    }
}
