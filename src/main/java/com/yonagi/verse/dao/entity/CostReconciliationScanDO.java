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
    /** 每日或启动触发身份。 */
    private String triggerKey;
    /** 不可延长的发现期限。 */
    private LocalDateTime discoveryDeadlineAt;
    /** 不可延长的执行期限。 */
    private LocalDateTime executionDeadlineAt;
    /** 控制消息可靠清理期限。 */
    private LocalDateTime cleanupDeadlineAt;
    /** 本次合法发现者令牌。 */
    private String producerToken;
    /** 发现者租期，提交页面时续期。 */
    private LocalDateTime producerLeaseUntil;
    /** 已原子登记的页数，同时作为下一个序号。 */
    private Long taskCount;
    /** 生产已冻结，之后不得追加页面。 */
    private Boolean productionClosed;
    /** 候选发现是否完整。 */
    private Boolean discoveryComplete;
    /** COMPLETE、PARTIAL 或 FAILED。 */
    private String completionReason;
    /** 唯一控制链接续序号。 */
    private Long controlSequence;
    /** 控制链需要下次合法触发修复。 */
    private Boolean controlRecoveryRequired;
    /** 通知暂停或失败，需可靠恢复。 */
    private Boolean notificationRecoveryRequired;
    /** 通知恢复版本，防止旧分页覆盖新失败。 */
    private Long notificationEpoch;
    /** 封存时固定的首次通知可靠期限。 */
    private LocalDateTime notificationDeadlineAt;
    /** 最近控制失败摘要。 */
    private String lastError;
}
