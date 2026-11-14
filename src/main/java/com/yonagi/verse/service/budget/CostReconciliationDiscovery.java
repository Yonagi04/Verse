package com.yonagi.verse.service.budget;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.CostReconciliationEvent;
import com.yonagi.verse.async.event.CostReconciliationTaskEvent;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.entity.CostReconciliationAttemptDO;
import com.yonagi.verse.dao.entity.CostReconciliationBatchDO;
import com.yonagi.verse.dao.entity.CostReconciliationCaseDO;
import com.yonagi.verse.dao.mapper.CostBudgetInvocationMapper;
import com.yonagi.verse.dao.mapper.CostBudgetSettlementMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAttemptMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationBatchMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class CostReconciliationDiscovery {
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final String CHARACTERS = LETTERS + "0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final CostBudgetService budgets;
    private final TenantCostBudgetLedger tenants;
    private final LlmCostBudgetLedger models;
    private final CostBudgetInvocationMapper invocations;
    private final CostBudgetSettlementMapper settlements;
    private final CostReconciliationAttemptMapper attempts;
    private final CostReconciliationPageBarrier barrier;
    private final CostReconciliationSignals signals;
    private final CostReconciliationScanMapper scans;
    private final CostReconciliationBatchMapper batches;
    private final CostReconciliationCaseMapper cases;
    private final ReconciliationEvidence evidence;
    private final ReliableDomainEventPublisher events;
    private final CostReconciliationTracking tracking;

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 5)
    public boolean classify(CostReconciliationTaskEvent page, CostBudgetInvocationDO observed, boolean ownerActive) {
        if (!barrier.admit(page)) {
            return false;
        }
        if (observed == null || !Objects.equals(observed.getTenantId(), page.getTenantId())) {
            throw new IllegalStateException("reconciliation page has no matching invocation");
        }
        tenants.lock(page.getTenantId());
        if (observed.getApiKeyId() != null) {
            budgets.lockKey(page.getTenantId(), observed.getApiKeyId());
        }
        var participants = models.lockForRequest(page.getTenantId(), observed.getRequestId(), null);
        var invocation = invocations.lockRequest(observed.getRequestId());
        models.verifyParticipants(observed.getRequestId(), participants);
        var original = settlements.lockEvent(observed.getEventId());
        var now = scans.databaseTime();
        if (invocation == null || !Objects.equals(invocation.getEventId(), observed.getEventId())
                || !Objects.equals(invocation.getTenantId(), page.getTenantId())
                || !Objects.equals(invocation.getReconciliationGeneration(), page.getGeneration())
                || invocation.getOriginalUserId() == null) {
            throw new IllegalStateException("reconciliation invocation identity changed");
        }
        if (!"TRACKING".equals(invocation.getReconciliationDisposition())) {
            return true;
        }
        if ("NO_UPSTREAM".equals(invocation.getState()) || original != null
                && Boolean.TRUE.equals(original.getBudgetApplied())
                && !CostStatus.UNCALCULABLE.name().equals(original.getCostStatus())) {
            invocation.setReconciliationDisposition("CLEAR");
            invocation.setUpdateTime(now);
            invocations.updateById(invocation);
            return true;
        }
        if (original == null && "RUNNING".equals(invocation.getState())
                && (ownerActive || invocation.getLeaseUntil() != null && now.isBefore(invocation.getLeaseUntil()))) {
            if (ownerActive) {
                invocation.setLastHeartbeatAt(now);
                invocation.setLeaseUntil(now.plusSeconds(30));
                invocation.setUpdateTime(now);
                invocations.updateById(invocation);
            }
            return true;
        }
        var attempt = attempts.latest(invocation.getRequestId());
        if (original == null && attempt != null && "INTENT".equals(attempt.getOutcome())
                && invocation.getLeaseUntil() != null && now.isBefore(invocation.getLeaseUntil().plusSeconds(30))) {
            return true;
        }
        if (original == null && attempt == null) {
            invocation.setState("NO_UPSTREAM");
            invocation.setReconciliationDisposition("CLEAR");
            invocation.setUpdateTime(now);
            invocations.updateById(invocation);
            return true;
        }
        var existing = cases.lockRequest(invocation.getTenantId(), invocation.getRequestId());
        boolean unapplied = original != null && !Boolean.TRUE.equals(original.getBudgetApplied());
        boolean canCorrect = evidence.automatic(attempt).status() != CostStatus.UNCALCULABLE;
        String state = unapplied || canCorrect ? "AUTO" : attempt == null ? "REVIEW" : "PENDING";
        var row = existing == null ? createCase(page, invocation, original, attempt, state) : existing;
        invocation.setReconciliationDisposition("CASE");
        if (!"FINALIZING".equals(invocation.getState())) {
            invocation.setState("UNKNOWN");
        }
        invocation.setUpdateTime(now);
        invocations.updateById(invocation);
        if (existing == null && "AUTO".equals(row.getState())) {
            var event = new CostReconciliationEvent();
            event.setEventId("cost-reconciliation-auto-" + row.getId());
            event.setKey(String.valueOf(row.getTenantId()));
            event.setAutomaticCaseId(row.getId());
            event.setAutomaticCaseVersion(row.getVersion());
            if (unapplied) {
                event.setStagedEventId(original.getEventId());
            }
            events.publish(event, row.getTenantId());
        }
        return true;
    }

    /** 原快照已应用但仍未知时，转人工并结束独立自动任务。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void transferAfterReplay(CostReconciliationCaseDO lookup, long expectedVersion) {
        tenants.lock(lookup.getTenantId());
        var invocation = invocations.lockRequest(lookup.getRequestId());
        var original = settlements.lockEvent(lookup.getEventId());
        var row = cases.lockInBatch(lookup.getBatchId(), lookup.getId());
        if (row == null || !"AUTO".equals(row.getState()) || row.getVersion() != expectedVersion) {
            return;
        }
        if (invocation == null || original == null || !Boolean.TRUE.equals(original.getBudgetApplied())) {
            throw new IllegalStateException("automatic replay incomplete");
        }
        if (CostStatus.UNCALCULABLE.name().equals(original.getCostStatus())) {
            var attempt = row.getAttemptId() == null ? null : attempts.selectById(row.getAttemptId());
            if (evidence.automatic(attempt).status() == CostStatus.CALCULATED) {
                // 用量可能在原快照重放期间补全。转人工前在同一请求锁下再核对一次。
                tracking.queueLateAutomatic(invocation, row);
                return;
            }
            row.setState(row.getAttemptId() == null ? "REVIEW" : "PENDING");
        } else {
            row.setState("RESOLVED");
            row.setResolvedAt(scans.databaseTime());
        }
        row.setVersion(Math.addExact(row.getVersion(), 1));
        cases.updateById(row);
        signals.batchChanged(row);
    }

    /** CASE 归属仍须处理 AUTO 超时；确认费用不得转为可人工覆盖金额。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 5)
    public boolean expireAutomatic(CostReconciliationTaskEvent page, CostReconciliationCaseDO lookup,
                                   long expectedVersion) {
        if (!barrier.admit(page)) {
            return false;
        }
        if (lookup == null || !Objects.equals(lookup.getTenantId(), page.getTenantId())) {
            throw new IllegalStateException("missing original automatic case");
        }
        tenants.lock(lookup.getTenantId());
        var invocation = invocations.lockRequest(lookup.getRequestId());
        var original = settlements.lockEvent(lookup.getEventId());
        var row = cases.lockInBatch(lookup.getBatchId(), lookup.getId());
        var run = scans.selectById(page.getRunId());
        if (row == null || !"AUTO".equals(row.getState()) || row.getVersion() != expectedVersion
                || row.getAutomaticDeadlineAt() == null || row.getAutomaticDeadlineAt().isAfter(run.getCutoffAt())) {
            return true;
        }
        if (invocation == null || !Objects.equals(invocation.getTenantId(), row.getTenantId())
                || !Objects.equals(invocation.getEventId(), row.getEventId())
                || !Objects.equals(invocation.getOriginalUserId(), row.getUserId())
                || !Objects.equals(invocation.getApiKeyId(), row.getApiKeyId())
                || !Objects.equals(invocation.getSource(), row.getSource())
                || !Objects.equals(invocation.getRequestStartedAt(), row.getRequestStartedAt())
                || invocation.getReconciliationGeneration() == null || invocation.getReconciliationGeneration() <= 0
                || !"CASE".equals(invocation.getReconciliationDisposition())) {
            throw new CostBudgetSnapshot.ConflictException();
        }
        var attempt = row.getAttemptId() == null ? null : attempts.selectById(row.getAttemptId());
        boolean confirmed = original != null && !Boolean.TRUE.equals(original.getBudgetApplied())
                && CostStatus.CALCULATED.name().equals(original.getCostStatus()) && !tracking.hasAdjustment(invocation);
        boolean canTransfer = attempt != null && evidence.price(attempt).priced()
                && evidence.automatic(attempt).status() == CostStatus.UNCALCULABLE
                && (original == null || CostStatus.UNCALCULABLE.name().equals(original.getCostStatus())) && !confirmed;
        row.setState(canTransfer ? "PENDING" : "REVIEW");
        row.setVersion(Math.addExact(row.getVersion(), 1));
        cases.updateById(row);
        signals.batchChanged(row);
        log.warn("[reconciliation] 自动处理超时 batchId={} caseId={} state={}",
                row.getBatchId(), row.getId(), row.getState());
        return true;
    }

    private CostReconciliationCaseDO createCase(CostReconciliationTaskEvent task, CostBudgetInvocationDO invocation,
                                               CostBudgetSettlementDO original, CostReconciliationAttemptDO attempt,
                                               String state) {
        var batch = tenantBatch(task.getRunId(), task.getTenantId());
        var row = new CostReconciliationCaseDO();
        row.setBatchId(batch.getId());
        row.setTenantId(task.getTenantId());
        row.setRequestId(invocation.getRequestId());
        row.setEventId(invocation.getEventId());
        row.setApiKeyId(invocation.getApiKeyId());
        row.setSource(invocation.getSource());
        row.setRequestStartedAt(invocation.getRequestStartedAt());
        row.setUserId(invocation.getOriginalUserId());
        row.setAttemptId(attempt == null ? null : attempt.getId());
        row.setState(state);
        row.setVersion(0L);
        if ("AUTO".equals(state)) {
            row.setAutomaticDeadlineAt(scans.databaseTime().plusMinutes(5));
        }
        if (attempt == null) {
            row.setReason("MISSING_ATTEMPT");
            log.error("[reconciliation] 缺少物理尝试: requestId={}", row.getRequestId());
        } else if (original != null && !Boolean.TRUE.equals(original.getBudgetApplied())) {
            row.setReason("UNAPPLIED_SETTLEMENT");
        } else if (evidence.price(attempt).billingMode() == BillingMode.REQUEST) {
            row.setReason("CHARGEABILITY_UNKNOWN");
            row.setEstimatedCostFen(evidence.price(attempt).requestPriceFen());
        } else {
            row.setReason("USAGE_INCOMPLETE");
        }
        cases.insert(row);
        return row;
    }

    private CostReconciliationBatchDO tenantBatch(Long scanId, Long tenantId) {
        var lookup = Wrappers.lambdaQuery(CostReconciliationBatchDO.class)
                .eq(CostReconciliationBatchDO::getScanId, scanId)
                .eq(CostReconciliationBatchDO::getTenantId, tenantId);
        var existing = batches.selectOne(lookup);
        if (existing != null) {
            return existing;
        }
        for (int retry = 0; retry < 5; retry++) {
            var row = new CostReconciliationBatchDO();
            row.setScanId(scanId);
            row.setTenantId(tenantId);
            row.setCode(batchCode());
            try {
                batches.insert(row);
                return row;
            } catch (DuplicateKeyException collision) {
                var concurrent = batches.selectOne(lookup);
                if (concurrent != null) {
                    return concurrent;
                }
                if (retry == 4) {
                    throw collision;
                }
            }
        }
        throw new IllegalStateException("reconciliation batch code unavailable");
    }

    private String batchCode() {
        var code = new StringBuilder(8).append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        for (int index = 1; index < 8; index++) {
            code.append(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
        }
        return code.toString();
    }
}
