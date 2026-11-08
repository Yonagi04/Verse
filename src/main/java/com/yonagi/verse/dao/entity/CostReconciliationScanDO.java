package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_cost_reconciliation_scan")
public class CostReconciliationScanDO {
    /** 启用标记或扫描轮次标识。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 启用标记或轮次开合状态。 */
    private String state;
    /** 启用代次，零表示仍在影子阶段。 */
    private Long generation;
    /** 候选到期范围上界，启用标记无截止时间。 */
    private LocalDateTime cutoffAt;
    /** 封存时刻。 */
    private LocalDateTime sealedAt;
}
