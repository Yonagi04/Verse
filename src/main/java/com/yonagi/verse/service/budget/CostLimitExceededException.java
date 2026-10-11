package com.yonagi.verse.service.budget;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.CostBudgetErrorCodeEnum;
import com.yonagi.verse.dto.resp.ApiKeyCostStatusRespDTO;
import lombok.Getter;

@Getter
public class CostLimitExceededException extends ClientException {
    private final CostBudgetDecision status;
    private String requestId;
    public CostLimitExceededException requestId(String value) { requestId = value; return this; }
    public CostLimitExceededException(ApiKeyCostStatusRespDTO status) {
        this(new CostBudgetDecision(status.limits(), false));
    }
    public CostLimitExceededException(CostBudgetDecision status) {
        super(("LLM".equals(status.limits().getFirst().scope()) ? "此模型 " : "此 API Key ") + switch (status.limits().getFirst().period()) {
            case "DAY" -> "本日"; case "WEEK" -> "本周"; default -> "本月";
        } + "的调用成本已达到限额，请联系管理员调整限额或等待相关周期重置后再重试。",
                CostBudgetErrorCodeEnum.LIMIT_EXCEEDED);
        this.status = status;
    }
}
