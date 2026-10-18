package com.yonagi.verse.service.usage.user;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.async.outbox.UsageOutboxStager;
import com.yonagi.verse.common.config.*;
import com.yonagi.verse.service.budget.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 价格计算关闭时仍可靠采集活动；不参与原预算结算或放宽准入。 */
@Service @RequiredArgsConstructor
public class UserModelUsageFactPublisher {
    private final UserModelUsageProperties properties;
    private final UsageOutboxProperties outboxProperties;
    private final UsageOutboxStager outbox;
    private final CostBudgetService budgets;
    private final BudgetPeriodResolver time;
    public boolean enabled() { return properties.isProjectionEnabled() && outboxProperties.isEnabled(); }
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Exception.class)
    public void publish(TokenUsageEvent event) {
        if (!enabled()) return;
        event.setEventId(budgets.usageEventId(event)); event.setKey(event.getEventId());
        outbox.stage(event,JSON.toJSONString(event),time.local(time.now()));
    }
}
