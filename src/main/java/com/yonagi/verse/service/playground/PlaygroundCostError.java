package com.yonagi.verse.service.playground;

import com.yonagi.verse.dto.playground.WorkbenchTypes.ErrorDataVO;
import com.yonagi.verse.dto.resp.ApiKeyCostStatusRespDTO.Hit;
import com.yonagi.verse.service.budget.CostBudgetDecision;
import com.yonagi.verse.service.budget.CostLimitExceededException;

/** 与 SSE 和发流前 Result 共用的脱敏拒绝详情。 */
public final class PlaygroundCostError {
    private PlaygroundCostError() { }
    /** 仅返回命中范围、周期和可重试时间，不携带预算金额。 */
    public static ErrorDataVO data(CostLimitExceededException error, String requestId) {
        CostBudgetDecision status = error.getStatus();
        Hit primary = status.limits().getFirst();
        return new ErrorDataVO(primary.scope(), primary.period(), status.retryAt(), "Asia/Shanghai",
                status.limits(), requestId);
    }
}
