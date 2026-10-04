package com.yonagi.verse.dao.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;
import java.math.BigDecimal;
/** 成本预算持久化记录。 */
@Data
@TableName("t_cost_budget_settlement")
public class CostBudgetSettlementDO {
    /** 账本主键。 */
    private Long id;
    /** 稳定终态事件标识。 */
    private String eventId;
    /** 租户标识。 */
    private Long tenantId;
    /** 用户标识。 */
    private Long userId;
    /** Key 标识。 */
    private Long apiKeyId;
    /** 实际服务标识。 */
    private Long serviceId;
    /** 调用来源。 */
    private String source;
    /** 调用能力。 */
    private String operation;
    /** 逻辑请求标识。 */
    private String requestId;
    /** 上海请求开始时间。 */
    private LocalDateTime requestStartedAt;
    /** 原始费用状态。 */
    private String costStatus;
    /** 原始预估费用，分。 */
    private BigDecimal estimatedCostFen;
    /** 币种。 */
    private String currency;
    /** 完整终态快照。 */
    private String eventPayloadJson;
    /** 规范快照摘要。 */
    private String payloadHash;
    /** 是否完成累计与 Outbox 接管。 */
    private Boolean budgetApplied;
    /** 实时或历史导入。 */
    private String origin;
    /** 暂存时间。 */
    private LocalDateTime createTime;
    /** 同步结算时间。 */
    private LocalDateTime appliedAt;
}
