package com.yonagi.verse.dao.projection;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PendingCostReconciliation {
    /** 当前周期需要自动、人工或运维核对的案件数量。 */
    private long pendingCount;
    /** 已锁定历史按次价格的暂计金额，分。 */
    private BigDecimal estimatedCostFen;
}
