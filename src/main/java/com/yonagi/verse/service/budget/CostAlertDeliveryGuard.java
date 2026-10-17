package com.yonagi.verse.service.budget;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.enums.MessageSubmissionStatus;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.service.messaging.MessageSendEligibility;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.util.List;

/** 两种成本作用域共享外发前复核，元数据只作 RC 非锁定读取。 */
@Service @RequiredArgsConstructor
public class CostAlertDeliveryGuard implements MessageSendEligibility {
    private final LlmCostBudgetLedger ledger;
    private final TenantCostBudgetLedger tenantLedger;
    private final TenantMapper tenants;
    private final LlmCostAlertEventMapper events;
    private final LlmCostAlertDeliveryMapper deliveries;
    private final UserMapper users;
    private final BudgetPeriodResolver time;

    public record State(boolean enabled, boolean ready, boolean warning, int threshold, List<String> channels,
            java.util.function.Function<String,BigDecimal> limit,
            java.util.function.Function<BudgetPeriodResolver.Period,CostBudgetPeriodDO> load,
            java.util.function.Supplier<CostBudgetDecision> decision) { }
    public State lockState(LlmCostAlertEventDO event) {
        if ("TENANT".equals(event.getScopeType())) {
            var c = tenantLedger.lock(event.getTenantId());
            return new State(Boolean.TRUE.equals(c.getCostLimitEnabled()), tenantLedger.ready(c,time.now()),
                    Boolean.TRUE.equals(c.getWarningEnabled()), c.getWarningThresholdPercent(), TenantCostBudgetLedger.channels(c),
                    p -> TenantCostBudgetLedger.limit(c,p), p -> tenantLedger.load(c,p), () -> tenantLedger.decision(c));
        }
        if (event.getScopeType() != null && !"LLM".equals(event.getScopeType())) throw new CostBudgetUnavailableException();
        var c = ledger.lock(event.getTenantId(),event.getServiceId());
        return new State(Boolean.TRUE.equals(c.getCostLimitEnabled()), ledger.ready(c,time.now()),
                Boolean.TRUE.equals(c.getWarningEnabled()), c.getWarningThresholdPercent(), LlmCostBudgetLedger.channels(c),
                p -> LlmCostBudgetLedger.limit(c,p), p -> ledger.load(c,p), () -> ledger.decision(c));
    }
    @Override @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MessageSubmissionRespDTO verify(String scene, String requestId) {
        if (!List.of("LLM_COST_WARNING","LLM_COST_LIMIT","TENANT_COST_WARNING","TENANT_COST_LIMIT").contains(scene)) return null;
        var delivery = deliveries.selectOne(Wrappers.lambdaQuery(LlmCostAlertDeliveryDO.class).eq(LlmCostAlertDeliveryDO::getRequestId,requestId));
        if (delivery == null) return cancelled("DELIVERY_MISSING");
        var event = events.selectOne(Wrappers.lambdaQuery(LlmCostAlertEventDO.class).eq(LlmCostAlertEventDO::getEventId,delivery.getEventId()));
        if (event == null) return cancelled("EVENT_MISSING");
        var config = lockState(event); events.lock(event.getEventId());
        String invalid = stateReason(event,config);
        if (invalid != null) return cancelled(invalid);
        if (!config.ready()) return MessageSubmissionRespDTO.retryable("BUDGET_UNAVAILABLE");
        delivery = deliveries.lock(delivery.getId());
        if (delivery == null || !List.of("PENDING","RETRY_WAIT","CLAIMED","QUEUED").contains(delivery.getStatus())) return cancelled("DELIVERY_CANCELLED");
        String recipient = recipientReason(event,delivery,config);
        return recipient == null ? null : cancelled(recipient);
    }
    public String stateReason(LlmCostAlertEventDO event, State config) {
        if (!event.getPeriodEnd().isAfter(time.local(time.now()))) return "PERIOD_EXPIRED";
        if (!config.enabled()) return "BUDGET_DISABLED";
        if (!config.ready()) return null;
        var period = new BudgetPeriodResolver.Period(event.getPeriodType(),event.getPeriodStart(),event.getPeriodEnd());
        String type = LlmCostAlertPolicy.event(true,config.limit().apply(event.getPeriodType()),config.load().apply(period).getUsedCostFen(),config.warning(),config.threshold());
        return event.getEventType().equals(type) ? null : "STATE_CHANGED";
    }
    public String recipientReason(LlmCostAlertEventDO event, LlmCostAlertDeliveryDO delivery, State config) {
        String role = tenants.recipientRole(event.getTenantId(),delivery.getRecipientUserId());
        if (role == null) return "RECIPIENT_LEFT";
        if (!"IN_APP".equals(delivery.getChannel()) && !List.of("ADMIN","SUPER_ADMIN").contains(role)) return "ROLE_CHANGED";
        if ("WARNING".equals(event.getEventType()) && !config.channels().contains(delivery.getChannel())) return "CHANNEL_REMOVED";
        if ("IN_APP".equals(delivery.getChannel())) return null;
        var user = users.selectOne(Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getUserId,delivery.getRecipientUserId()).eq(UserDO::getStatus,1).eq(UserDO::getDelFlag,0));
        String hash = user == null ? null : "SMS".equals(delivery.getChannel()) ? user.getPhoneHash() : user.getEmailHash();
        return hash != null && hash.equals(delivery.getContactHash()) ? null : "CONTACT_CHANGED";
    }
    private MessageSubmissionRespDTO cancelled(String reason) { return new MessageSubmissionRespDTO(MessageSubmissionStatus.CANCELLED,null,null,reason); }
}
