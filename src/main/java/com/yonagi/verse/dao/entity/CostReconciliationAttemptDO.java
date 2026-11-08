package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_cost_reconciliation_attempt")
public class CostReconciliationAttemptDO {
    /** 物理尝试标识。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 原调用标识。 */
    private String requestId;
    /** 物理尝试序号。 */
    private Integer attemptNo;
    /** 实际模型。 */
    private Long serviceId;
    /** 实际供应商。 */
    private String provider;
    /** 实际操作。 */
    private String operation;
    /** 原时点定价快照。 */
    private String pricingJson;
    /** 已持久化的发送或终态证据。 */
    private String outcome;
    /** 上游分类用量。 */
    private String usageJson;
}
