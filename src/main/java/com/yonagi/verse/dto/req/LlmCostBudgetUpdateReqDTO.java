package com.yonagi.verse.dto.req;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Data;

/** 预算专用维护，不改变模型启停状态。 */
@Data
public class LlmCostBudgetUpdateReqDTO {
    /** 限额补丁及共同配置版本。 */
    @JsonSetter(nulls = Nulls.FAIL) private CostLimitPatch costLimit;
    /** 预警补丁。 */
    @JsonSetter(nulls = Nulls.FAIL) private CostWarningPatch costWarning;
}
