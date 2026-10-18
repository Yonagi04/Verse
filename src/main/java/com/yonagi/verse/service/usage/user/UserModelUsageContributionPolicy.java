package com.yonagi.verse.service.usage.user;

import com.yonagi.verse.dao.entity.*;
import java.math.BigDecimal;
import java.util.Objects;

/** 活动成功与费用可计算是两个独立事实，不用展示舍入后的数值判阈值。 */
public final class UserModelUsageContributionPolicy {
    private static final BigDecimal MAX_TOTAL = new BigDecimal("99999999999999999999.999999999999999999");
    private UserModelUsageContributionPolicy() { }
    public record Contribution(long successes, BigDecimal cost, long unknown) { }
    public static Contribution contribution(TokenUsageDO usage, TokenUsageCostDO cost) {
        long successes = "SUCCESS".equals(usage.getStatus()) ? 1 : 0;
        if (cost == null) return new Contribution(successes,BigDecimal.ZERO,1);
        if ("NOT_CHARGEABLE".equals(cost.getCostStatus()) && cost.getEstimatedCostFen() == null)
            return new Contribution(successes,BigDecimal.ZERO,0);
        if ("CALCULATED".equals(cost.getCostStatus())) {
            BigDecimal amount = cost.getEstimatedCostFen();
            if (amount == null || amount.signum()<0 || !"CNY".equals(cost.getCurrency()) || amount.scale()>18 || amount.compareTo(MAX_TOTAL)>0)
                throw new IllegalArgumentException("INVALID_COST");
            return new Contribution(successes,amount,0);
        }
        if (cost.getEstimatedCostFen()!=null) throw new IllegalArgumentException("INVALID_COST_STATE");
        return new Contribution(successes,BigDecimal.ZERO,1);
    }
    public static boolean equivalent(TokenUsageDO a,TokenUsageCostDO ac,TokenUsageDO b,TokenUsageCostDO bc) {
        return Objects.equals(a.getUserId(),b.getUserId()) && Objects.equals(a.getTenantId(),b.getTenantId())
                && Objects.equals(a.getSource(),b.getSource()) && Objects.equals(a.getRequestId(),b.getRequestId())
                && Objects.equals(a.getRequestStartedAt(),b.getRequestStartedAt()) && Objects.equals(a.getStatus(),b.getStatus())
                && Objects.equals(a.getServiceId(),b.getServiceId()) && Objects.equals(a.getOperation(),b.getOperation())
                && ac!=null && bc!=null && Objects.equals(ac.getCostStatus(),bc.getCostStatus())
                && Objects.equals(ac.getCurrency(),bc.getCurrency()) && equalAmount(ac.getEstimatedCostFen(),bc.getEstimatedCostFen());
    }
    private static boolean equalAmount(BigDecimal a, BigDecimal b) { return a==null ? b==null : b!=null && a.compareTo(b)==0; }
    public static void checkTotal(BigDecimal value) {
        if (value.signum()<0 || value.compareTo(MAX_TOTAL)>0) throw new IllegalArgumentException("COST_OVERFLOW");
    }
}
