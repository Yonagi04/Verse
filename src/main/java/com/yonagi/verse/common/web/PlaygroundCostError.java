package com.yonagi.verse.common.web;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.service.budget.CostLimitExceededException;
/** 与 SSE 和发流前 Result 共用的脱敏拒绝详情。 */
public final class PlaygroundCostError {
    private PlaygroundCostError() { }
    public static JSONObject data(CostLimitExceededException error, String requestId) {
        var status=error.getStatus(); var primary=status.limits().getFirst();
        var data = JSONObject.of("scope",primary.scope(),"period",primary.period(),"retryAt",status.retryAt(),
                "timezone","Asia/Shanghai","limits",status.limits());
        data.put("requestId", requestId); return data;
    }
}
