package com.yonagi.verse.service.budget;

import com.yonagi.verse.dto.resp.ApiKeyCostStatusRespDTO.Hit;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/** 只携带拒绝原因，不让调用错误依赖包含金额的管理视图。 */
public record CostBudgetDecision(List<Hit> limits, boolean unavailable) {
    public CostBudgetDecision {
        limits = limits.stream().sorted(Comparator
                .comparing((Hit hit) -> OffsetDateTime.parse(hit.periodEnd()).toInstant()).reversed()
                .thenComparingInt(hit -> switch (hit.scope()) { case "TENANT" -> 0; case "API_KEY" -> 1; default -> 2; })
                .thenComparingInt(hit -> switch (hit.period()) { case "MONTH" -> 0; case "WEEK" -> 1; default -> 2; }))
                .toList();
    }
    public String retryAt() { return limits.isEmpty() ? null : limits.getFirst().periodEnd(); }
    public void enforce() {
        if (unavailable) throw new CostBudgetUnavailableException();
        if (!limits.isEmpty()) throw new CostLimitExceededException(this);
    }
}
