package com.yonagi.verse.async.handler;
import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEventHandler;
import com.yonagi.verse.async.event.LlmCostAlertEvent;
import com.yonagi.verse.service.budget.LlmCostAlertDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class LlmCostAlertEventHandler implements DomainEventHandler<LlmCostAlertEvent> {
    private final LlmCostAlertDeliveryService delivery;
    @Override public String eventType() { return EventTag.LLM_COST_ALERT; }
    @Override public Class<LlmCostAlertEvent> eventClass() { return LlmCostAlertEvent.class; }
    @Override public void onEvent(LlmCostAlertEvent event) { delivery.wake(event.getAlertEventId()); }
}
