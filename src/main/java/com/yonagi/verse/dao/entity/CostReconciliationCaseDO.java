package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_cost_reconciliation_case")
public class CostReconciliationCaseDO {
    /** 对账案件标识。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 首次发现批次。 */
    private Long batchId;
    /** 原租户。 */
    private Long tenantId;
    /** 原调用。 */
    private String requestId;
    /** 原调用用量事件标识，用于合并更正与原快照。 */
    private String eventId;
    /** 原调用 Key，Playground 为空。 */
    private Long apiKeyId;
    /** 原调用来源。 */
    private String source;
    /** 原调用开始时间，决定账务归属周期。 */
    private LocalDateTime requestStartedAt;
    /** 费用对应的物理尝试。 */
    private Long attemptId;
    /** 原调用用户。 */
    private Long userId;
    /** 案件处理状态。 */
    private String state;
    /** 首次发现原因。 */
    private String reason;
    /** 案件版本。 */
    private Long version;
    /** 暂计金额，分。 */
    private BigDecimal estimatedCostFen;
    /** 迟到权威证据需要运维复核。 */
    private Boolean reviewRequired;
    /** 结案时间。 */
    private LocalDateTime resolvedAt;
}
