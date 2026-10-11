package com.yonagi.verse.service.budget;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.enums.MessageSubmissionStatus;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.service.messaging.MessageSendEligibility;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** messaging 只依赖资格 Port，预算校验与接收人规则留在所属领域。 */
@Service @RequiredArgsConstructor
public class CostAlertDeliveryGuard implements MessageSendEligibility {
    private final LlmCostBudgetLedger ledger;
    private final LlmCostBudgetMapper budgets;
    private final LlmCostAlertEventMapper events;
    private final LlmCostAlertDeliveryMapper deliveries;
    private final UserMapper users;
    private final BudgetPeriodResolver time;

    @Override @Transactional(rollbackFor = Exception.class)
    public MessageSubmissionRespDTO verify(String scene, String requestId) {
        if (!"LLM_COST_WARNING".equals(scene) && !"LLM_COST_LIMIT".equals(scene)) return null;
        var delivery = deliveries.selectOne(Wrappers.lambdaQuery(LlmCostAlertDeliveryDO.class).eq(LlmCostAlertDeliveryDO::getRequestId, requestId));
        if (delivery == null) return cancelled("DELIVERY_MISSING");
        var event = events.selectOne(Wrappers.lambdaQuery(LlmCostAlertEventDO.class).eq(LlmCostAlertEventDO::getEventId, delivery.getEventId()));
        if (event == null) return cancelled("EVENT_MISSING");
        var config = ledger.lock(event.getTenantId(), event.getServiceId());
        String invalid = stateReason(event, config);
        if (invalid != null) return cancelled(invalid);
        if (!ledger.ready(config, time.now())) return MessageSubmissionRespDTO.retryable("BUDGET_UNAVAILABLE");
        delivery = deliveries.lock(delivery.getId());
        if (delivery == null || !java.util.List.of("PENDING", "RETRY_WAIT", "CLAIMED", "QUEUED").contains(delivery.getStatus()))
            return cancelled("DELIVERY_CANCELLED");
        String recipient = recipientReason(event, delivery);
        return recipient == null ? null : cancelled(recipient);
    }
    /** 调用方持有模型预算锁；此检查不改变历史事实。 */
    public String stateReason(LlmCostAlertEventDO event, LlmCostBudgetDO config) {
        if (!event.getPeriodEnd().isAfter(time.local(time.now()))) return "PERIOD_EXPIRED";
        if (!Boolean.TRUE.equals(config.getCostLimitEnabled())) return "BUDGET_DISABLED";
        if (!ledger.ready(config, time.now())) return null;
        var period = new BudgetPeriodResolver.Period(event.getPeriodType(), event.getPeriodStart(), event.getPeriodEnd());
        var current = ledger.load(config, period);
        String type = LlmCostAlertPolicy.event(true, LlmCostBudgetLedger.limit(config, event.getPeriodType()), current.getUsedCostFen(),
                Boolean.TRUE.equals(config.getWarningEnabled()), config.getWarningThresholdPercent());
        if (!event.getEventType().equals(type)) return "STATE_CHANGED";
        return null;
    }
    public String recipientReason(LlmCostAlertEventDO event, LlmCostAlertDeliveryDO delivery) {
        String role = budgets.role(event.getTenantId(), delivery.getRecipientUserId());
        if (role == null) return "RECIPIENT_LEFT";
        if (!"IN_APP".equals(delivery.getChannel()) && !"ADMIN".equals(role) && !"SUPER_ADMIN".equals(role)) return "ROLE_CHANGED";
        if ("WARNING".equals(event.getEventType())) {
            var config = ledger.lock(event.getTenantId(), event.getServiceId());
            if (!LlmCostBudgetLedger.channels(config).contains(delivery.getChannel())) return "CHANNEL_REMOVED";
        }
        if ("IN_APP".equals(delivery.getChannel())) return null;
        var user = users.selectOne(Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getUserId, delivery.getRecipientUserId())
                .eq(UserDO::getStatus, 1).eq(UserDO::getDelFlag, 0).last("FOR SHARE"));
        String hash = user == null ? null : "SMS".equals(delivery.getChannel()) ? user.getPhoneHash() : user.getEmailHash();
        return hash != null && hash.equals(delivery.getContactHash()) ? null : "CONTACT_CHANGED";
    }
    private MessageSubmissionRespDTO cancelled(String reason) {
        return new MessageSubmissionRespDTO(MessageSubmissionStatus.CANCELLED, null, null, reason);
    }
}
