package com.yonagi.verse.async.event;

import com.yonagi.verse.async.api.DomainEvent;
import lombok.Getter;
import lombok.Setter;

/** 系统运行控制，不发现新业务候选。 */
@Getter
@Setter
public class CostReconciliationControlEvent extends DomainEvent {
    public static final String TYPE = "COST_RECONCILIATION_CONTROL";
    /** 进度核验、唯一超时链、封存通知分页、指定原批次通知。 */
    public enum Kind {
        /** 只核验进度，不建立平行控制链。 */
        PROGRESS,
        /** 唯一有限超时控制链。 */
        CHECK,
        /** 已封存运行的通知分页。 */
        RUN_SEALED,
        /** 指定业务原批次的通知接续。 */
        CHECK_BATCH_NOTIFICATION
    }
    /** 消息协议版本。 */
    private Integer schemaVersion;
    /** 目标运行，通知事件必须是原批次所属运行。 */
    private Long runId;
    /** 控制职责。 */
    private Kind kind;
    /** 稳定控制链或恢复序号。 */
    private Long sequence;
    /** 通知恢复版本快照。 */
    private Long notificationEpoch;
    /** 指定原批次，其他控制为空。 */
    private Long batchId;
    /** 封存通知的批次分页游标。 */
    private Long afterBatchId;

    @Override
    public String eventType() { return TYPE; }
}
