package com.yonagi.verse.dao.projection;

import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import lombok.Getter;
import lombok.Setter;

/** 一次批量检查所需的参与模型及执行证明。 */
@Getter @Setter
public class LlmBudgetPendingInvocation extends CostBudgetInvocationDO {
    /** 参与模型业务 ID。 */
    private Long serviceId;
}
