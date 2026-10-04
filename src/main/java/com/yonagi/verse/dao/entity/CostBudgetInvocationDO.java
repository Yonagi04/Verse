package com.yonagi.verse.dao.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;
import java.math.BigDecimal;
/** 成本预算持久化记录。 */
@Data
@TableName("t_cost_budget_invocation")
public class CostBudgetInvocationDO {
    /** 调用接管主键。 */
    private Long id;
    /** 租户标识。 */
    private Long tenantId;
    /** Key 标识。 */
    private Long apiKeyId;
    /** 唯一逻辑请求标识。 */
    private String requestId;
    /** 预分配终态标识。 */
    private String eventId;
    /** 原始请求开始时间。 */
    private LocalDateTime requestStartedAt;
    /** 执行实例标识。 */
    private String ownerId;
    /** 本次进程启动代次。 */
    private String ownerGeneration;
    /** 执行或终态状态。 */
    private String state;
    /** 租约结束。 */
    private LocalDateTime leaseUntil;
    /** 最近执行心跳。 */
    private LocalDateTime lastHeartbeatAt;
    /** 恢复原因。 */
    private String lastErrorCode;
    /** 更新时间。 */
    private LocalDateTime updateTime;
}
