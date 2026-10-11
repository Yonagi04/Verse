package com.yonagi.verse.service.budget;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.common.enums.CostStatus;
import java.math.BigDecimal;
import java.util.*;

/** 在线结算和离线迁移共用的费用快照契约，禁止重新计价。 */
public final class CostBudgetSnapshot {
    private CostBudgetSnapshot() { }

    /** 相同事件身份携带不同快照时，不能用后到数据覆盖已入账费用。 */
    public static final class ConflictException extends IllegalStateException {
        public ConflictException() { super("settlement snapshot conflict"); }
    }
    public static String fingerprint(TokenUsageEvent event) {
        JSONObject tree = JSON.parseObject(JSON.toJSONString(event));
        tree.remove("occurredAt"); tree.remove("key");
        return DigestUtil.sha256Hex(JSON.toJSONString(sorted(tree)));
    }
    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((key, item) -> ordered.put(key.toString(), sorted(item))); return ordered;
        }
        if (value instanceof List<?> list) return list.stream().map(CostBudgetSnapshot::sorted).toList();
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros();
        return value;
    }
    public static void validate(TokenUsageEvent event) {
        if (event.getEventId() == null || event.getEventId().isBlank() || event.getRequestId() == null || event.getRequestId().isBlank()
                || event.getTenantId() == null || event.getServiceId() == null || event.getUserId() == null || event.getOperation() == null
                || event.getRequestStartedAt() == null || event.getCostResult() == null || event.getCostResult().status() == null
                || !("API_KEY".equals(event.getSource()) && event.getApiKeyId() != null
                || "PLAYGROUND".equals(event.getSource()) && event.getApiKeyId() == null)) {
            throw new IllegalStateException("incomplete original cost snapshot: event=" + event.getEventId());
        }
        BigDecimal amount = event.getCostResult().estimatedCostFen();
        if ((event.getCostResult().status() == CostStatus.CALCULATED) != (amount != null)
                || amount != null && (amount.signum() < 0 || amount.scale() > 18 || amount.precision() - amount.scale() > 20)
                || event.getPricingSnapshot() != null && event.getPricingSnapshot().currency() != null
                && !"CNY".equals(event.getPricingSnapshot().currency())) {
            throw new IllegalStateException("invalid original cost snapshot: event=" + event.getEventId());
        }
    }

}
