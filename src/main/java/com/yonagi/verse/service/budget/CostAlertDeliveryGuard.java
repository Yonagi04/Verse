package com.yonagi.verse.service.budget;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.enums.MessageSubmissionStatus;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.service.messaging.MessageSendEligibility;
import com.yonagi.verse.service.messaging.MessageSendAttemptPolicy;
import com.yonagi.verse.service.messaging.SendAttemptContext;
import com.yonagi.verse.service.messaging.SendRecordStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** 两种成本作用域共享外发前复核，元数据只作 RC 非锁定读取。 */
@Service @RequiredArgsConstructor
public class CostAlertDeliveryGuard implements MessageSendEligibility {
    private static final Set<String> COST_ALERT_SCENES = Set.of(
            "LLM_COST_WARNING", "LLM_COST_LIMIT", "TENANT_COST_WARNING", "TENANT_COST_LIMIT");
    private static final Set<String> ACTIVE_DELIVERY_STATUSES = Set.of(
            "PENDING", "RETRY_WAIT", "CLAIMED", "QUEUED");
    private final LlmCostBudgetLedger ledger;
    private final TenantCostBudgetLedger tenantLedger;
    private final TenantMapper tenants;
    private final LlmCostAlertEventMapper events;
    private final LlmCostAlertDeliveryMapper deliveries;
    private final UserMapper users;
    private final SendRecordStore records;
    private final BudgetPeriodResolver time;

    public record State(boolean enabled, boolean ready, boolean warning, int threshold, List<String> channels,
            java.util.function.Function<String,BigDecimal> limit,
            java.util.function.Function<BudgetPeriodResolver.Period,CostBudgetPeriodDO> load,
            java.util.function.Supplier<CostBudgetDecision> decision) { }
    public State lockState(LlmCostAlertEventDO event) {
        var now = time.now();
        if ("TENANT".equals(event.getScopeType())) {
            var c = tenantLedger.lock(event.getTenantId());
            boolean ready = tenantLedger.ready(c, now);
            var staged = ready ? tenantLedger.stagedCosts(c.getTenantId(), now) : CostReconciliationBudgetRead.StagedCosts.EMPTY;
            return new State(Boolean.TRUE.equals(c.getCostLimitEnabled()), ready && staged.available(),
                    Boolean.TRUE.equals(c.getWarningEnabled()), c.getWarningThresholdPercent(), TenantCostBudgetLedger.channels(c),
                    p -> TenantCostBudgetLedger.limit(c,p), p -> staged.include(tenantLedger.load(c,p), p, null, null),
                    () -> tenantLedger.decision(c));
        }
        if (event.getScopeType() != null && !"LLM".equals(event.getScopeType())) throw new CostBudgetUnavailableException();
        var c = ledger.lock(event.getTenantId(),event.getServiceId());
        boolean ready = ledger.ready(c, now);
        var staged = ready ? ledger.stagedCosts(c.getTenantId(), now) : CostReconciliationBudgetRead.StagedCosts.EMPTY;
        return new State(Boolean.TRUE.equals(c.getCostLimitEnabled()), ready && staged.available(),
                Boolean.TRUE.equals(c.getWarningEnabled()), c.getWarningThresholdPercent(), LlmCostBudgetLedger.channels(c),
                p -> LlmCostBudgetLedger.limit(c,p), p -> staged.include(ledger.load(c,p), p, null, c.getServiceId()),
                () -> ledger.decision(c));
    }
    /** 缺少当前消息尝试的旧入口不能授予成本告警外发许可。 */
    @Override
    public MessageSubmissionRespDTO verify(String scene, String requestId) {
        return supports(scene) ? cancelled("ATTEMPT_CONTEXT_REQUIRED") : null;
    }

    /** 对实际收件哈希和当前消息行授权，事务结束后才由 Worker 调用服务商。 */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MessageSubmissionRespDTO verify(String scene, String requestId, SendAttemptContext attempt) {
        if (!supports(scene)) {
            return null;
        }
        if (!MessageSendAttemptPolicy.hasRecordIdentity(attempt) || attempt.executionToken() == null
                || attempt.executionToken().isBlank() || attempt.attemptCount() == null || attempt.attemptCount() <= 0) {
            return cancelled("ATTEMPT_CONTEXT_REQUIRED");
        }
        LlmCostAlertDeliveryDO delivery = deliveries.selectOne(Wrappers.lambdaQuery(LlmCostAlertDeliveryDO.class)
                .eq(LlmCostAlertDeliveryDO::getRequestId, requestId));
        if (delivery == null) {
            return cancelled("DELIVERY_MISSING");
        }
        LlmCostAlertEventDO event = events.selectOne(Wrappers.lambdaQuery(LlmCostAlertEventDO.class)
                .eq(LlmCostAlertEventDO::getEventId, delivery.getEventId()));
        if (event == null) {
            return cancelled("EVENT_MISSING");
        }
        String expectedScene = ("TENANT".equals(event.getScopeType()) ? "TENANT_COST_" : "LLM_COST_")
                + event.getEventType();
        if (!expectedScene.equals(scene)) {
            return cancelled("SEND_ATTEMPT_CHANGED");
        }
        State config = lockState(event);
        events.lock(event.getEventId());
        String invalid = stateReason(event, config);
        if (invalid != null) {
            return cancelled(invalid);
        }
        if (!config.ready()) {
            return MessageSubmissionRespDTO.retryable("BUDGET_UNAVAILABLE");
        }
        delivery = deliveries.lock(delivery.getId());
        if (delivery == null || !ACTIVE_DELIVERY_STATUSES.contains(delivery.getStatus())) {
            return cancelled("DELIVERY_CANCELLED");
        }
        String recipient = recipientReason(event, delivery, config);
        if (recipient != null) {
            return cancelled(recipient);
        }
        String contactHash = delivery.getContactHash();
        if (!attempt.channel().name().equals(delivery.getChannel()) || contactHash == null || contactHash.isBlank()
                || !contactHash.equals(attempt.recipientHash())) {
            return cancelled("CONTACT_CHANGED");
        }
        // 消息锁位于预算、事件和任务锁之后；不能在此补取用户或成员锁。
        if (!records.lockAttempt(scene, delivery.getRequestId(), delivery.getRecipientUserId(),
                event.getTenantId(), attempt)) {
            return cancelled("SEND_ATTEMPT_CHANGED");
        }
        return null;
    }

    private boolean supports(String scene) {
        return scene != null && COST_ALERT_SCENES.contains(scene);
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
