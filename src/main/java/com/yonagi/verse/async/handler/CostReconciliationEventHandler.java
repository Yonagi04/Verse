package com.yonagi.verse.async.handler;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEventHandler;
import com.yonagi.verse.async.event.CostReconciliationEvent;
import com.yonagi.verse.dao.mapper.CostReconciliationAdjustmentMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import com.yonagi.verse.service.UsageProjectionService;
import com.yonagi.verse.service.budget.CostReconciliationNotifications;
import com.yonagi.verse.service.budget.CostReconciliationDiscovery;
import com.yonagi.verse.service.budget.CostReconciliationCorrectionService;
import com.yonagi.verse.service.budget.CostBudgetService;
import com.yonagi.verse.service.budget.CostBudgetUnavailableException;
import com.yonagi.verse.service.usage.user.UserModelUsageProjectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class CostReconciliationEventHandler implements DomainEventHandler<CostReconciliationEvent> {
    private final CostReconciliationNotifications notifications;
    private final CostReconciliationAdjustmentMapper adjustments;
    private final CostReconciliationCaseMapper cases;
    private final UsageProjectionService reports;
    private final UserModelUsageProjectionService personal;
    private final CostReconciliationCorrectionService corrections;
    private final CostReconciliationDiscovery discovery;
    private final CostBudgetService budgets;
    private final DomainEventOutboxMapper outbox;

    @Override
    public String eventType() {
        return EventTag.COST_RECONCILIATION;
    }

    @Override
    public Class<CostReconciliationEvent> eventClass() {
        return CostReconciliationEvent.class;
    }

    @Override
    public void onEvent(CostReconciliationEvent event) {
        dispatch(event);
        outbox.reconcileCostReconciliation(event.getEventId(), LocalDateTime.now());
    }

    private void dispatch(CostReconciliationEvent event) {
        if (event.getBatchId() != null) {
            notifications.enqueue(event.getBatchId());
            return;
        }
        if (event.getAutomaticCaseId() != null) {
            automatic(event);
            return;
        }
        var adjustment = adjustments.selectById(event.getAdjustmentId());
        var row = adjustment == null ? null : cases.selectById(adjustment.getCaseId());
        if (row == null) {
            throw new IllegalStateException("missing reconciliation adjustment");
        }
        var hour = row.getRequestStartedAt().truncatedTo(ChronoUnit.HOURS);
        if ("API_KEY".equals(row.getSource())) {
            reports.rebuildTenantHour(row.getTenantId(), hour);
        }
        personal.correctedDay(row.getUserId(), hour.toLocalDate());
        if (!budgets.captureConfirmedAlerts(row.getTenantId(), adjustment.getServiceId(), row.getRequestStartedAt())) {
            throw new CostBudgetUnavailableException();
        }
    }

    private void automatic(CostReconciliationEvent event) {
        var row = cases.selectById(event.getAutomaticCaseId());
        if (row == null) {
            throw new IllegalStateException("missing automatic reconciliation case");
        }
        long expectedVersion = event.getAutomaticCaseVersion() == null ? 0 : event.getAutomaticCaseVersion();
        if (event.getStagedEventId() != null) {
            if (CostReconciliationCorrectionService.acceptsAutomatic(row, expectedVersion)) {
                budgets.applyReconciliationReplay(event.getStagedEventId(), row.getId(), expectedVersion);
                discovery.transferAfterReplay(row, expectedVersion);
            }
        } else if (CostReconciliationCorrectionService.acceptsAutomatic(row, expectedVersion)) {
            corrections.automatic(row, expectedVersion);
        }
    }
}
