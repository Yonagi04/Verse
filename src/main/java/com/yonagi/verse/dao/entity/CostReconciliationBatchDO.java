package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_cost_reconciliation_batch")
public class CostReconciliationBatchDO {
    /** 租户批次标识。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 扫描轮次。 */
    private Long scanId;
    /** 归属租户。 */
    private Long tenantId;
    /** 登录后的批次定位码。 */
    private String code;
    /** 通知事件提交时间。 */
    private LocalDateTime notifiedAt;
    /** 固定收件组和加密联系方式。 */
    private String recipientsJson;
}
