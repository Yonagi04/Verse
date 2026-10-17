package com.yonagi.verse.async.event;
import com.yonagi.verse.async.api.DomainEvent;
import com.yonagi.verse.async.EventTag;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter
public class LlmCostAlertEvent extends DomainEvent {
    /** 所属租户。 */ private Long tenantId;
    /** 本地告警标识。 */ private String alertEventId;
    @Override public String eventType() { return EventTag.COST_ALERT; }
}
