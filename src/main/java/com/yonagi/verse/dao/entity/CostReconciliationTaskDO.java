package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 已生成页面的不可变清单及终态屏障；不用于工作认领。 */
@Data
@TableName("t_cost_reconciliation_task")
public class CostReconciliationTaskDO {
    /** 清单主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 稳定任务及 WORK 事件标识。 */
    private String taskId;
    /** 所属运行。 */
    private Long scanId;
    /** 运行内单调页序号。 */
    private Long pageSequence;
    /** 唯一业务租户。 */
    private Long tenantId;
    /** 分类请求或过期自动案件。 */
    private String operation;
    /** 规范化成员摘要。 */
    private String payloadHash;
    /** 页面成员数。 */
    private Integer itemCount;
    /** 与运行一致且不可变的执行截止时间。 */
    private LocalDateTime executionDeadlineAt;
    /** 已提交页最后成员的到期时间，NULL 表示请求空租约流。 */
    private LocalDateTime cursorAt;
    /** 已提交页最后成员的业务主键。 */
    private Long cursorId;
    /** PENDING、DONE、FAILED 或 EXPIRED。 */
    private String outcome;
    /** 终态原因。 */
    private String reason;
    /** 页面生成时间。 */
    private LocalDateTime createdAt;
    /** 不可逆终态时间。 */
    private LocalDateTime finishedAt;
}
