package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_cost_reconciliation_work")
public class CostReconciliationWorkDO {
    /** 恢复工作标识。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 原调用标识。 */
    private String requestId;
    /** 原租户。 */
    private Long tenantId;
    /** 原调用用户。 */
    private Long userId;
    /** 记录时的启用代次。 */
    private Long generation;
    /** 恢复或重新认领时间。 */
    private LocalDateTime nextActionAt;
    /** 正在分类的轮次。 */
    private Long scanId;
    /** 认领版本。 */
    private Long claimVersion;
    /** 首次分类结束并已可靠提交自动工作。 */
    private Boolean autoQueued;
    /** 已暂存确认费用。 */
    private Boolean pendingConfirmed;
    /** 原调用开始时间。 */
    private LocalDateTime requestStartedAt;
}
