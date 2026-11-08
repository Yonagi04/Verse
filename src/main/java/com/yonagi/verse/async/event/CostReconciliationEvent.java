package com.yonagi.verse.async.event;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEvent;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CostReconciliationEvent extends DomainEvent {
    /** 有更正事实时为更正 ID。 */
    private Long adjustmentId;
    /** 有通知任务时为租户批次 ID。 */
    private Long batchId;
    /** 首次分类后需要自动结案的案件。 */
    private Long automaticCaseId;
    /** 自动任务针对的案件版本。旧事件缺省为首次分类版本 0。 */
    private Long automaticCaseVersion;
    /** 需要重放时使用不可变原终态事件 ID。 */
    private String stagedEventId;

    @Override
    public String eventType() {
        return EventTag.COST_RECONCILIATION;
    }
}
