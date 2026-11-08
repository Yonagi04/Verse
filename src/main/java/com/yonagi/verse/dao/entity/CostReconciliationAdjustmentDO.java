package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_cost_reconciliation_adjustment")
public class CostReconciliationAdjustmentDO {
    /** 不可变更正标识。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 原案件。 */
    private Long caseId;
    /** 该版本确认的物理尝试。 */
    private Long attemptId;
    /** 该物理尝试的实际计费模型。 */
    private Long serviceId;
    /** 该物理尝试的上游操作。 */
    private String operation;
    /** 生效案件版本。 */
    private Long version;
    /** 提交幂等标识。 */
    private String submissionKey;
    /** 规范化提交摘要。 */
    private String fingerprint;
    /** 操作者，系统结案为空。 */
    private Long actorId;
    /** 生效的费用状态。 */
    private String costStatus;
    /** 按历史价格计算的费用，分。 */
    private BigDecimal costFen;
    /** 确认的分类用量。 */
    private String usageJson;
    /** 更正创建时间。 */
    private LocalDateTime createdAt;
}
