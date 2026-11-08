package com.yonagi.verse.service.budget;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.CostReconciliationEvent;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.entity.CostReconciliationAttemptDO;
import com.yonagi.verse.dao.entity.CostReconciliationBatchDO;
import com.yonagi.verse.dao.entity.CostReconciliationCaseDO;
import com.yonagi.verse.dao.entity.CostReconciliationWorkDO;
import com.yonagi.verse.dao.mapper.CostBudgetInvocationMapper;
import com.yonagi.verse.dao.mapper.CostBudgetSettlementMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAttemptMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationBatchMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationWorkMapper;
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
    private final CostReconciliationWorkMapper work;
    private final CostReconciliationScanMapper scans;
    private final CostReconciliationBatchMapper batches;
    private final CostReconciliationCaseMapper cases;
    private final ReconciliationEvidence evidence;
    private final ReliableDomainEventPublisher events;
    private final CostReconciliationTracking tracking;

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void classify(CostReconciliationWorkDO claim, CostBudgetInvocationDO observed, boolean ownerActive) {
        if (observed == null || !Objects.equals(observed.getTenantId(), claim.getTenantId())) {
            throw new IllegalStateException("reconciliation work has no matching invocation");
        }
        tenants.lock(claim.getTenantId());
        if (observed.getApiKeyId() != null) {
            budgets.lockKey(claim.getTenantId(), observed.getApiKeyId());
        }
        var participants = models.lockForRequest(claim.getTenantId(), claim.getRequestId(), null);
        var invocation = invocations.lockRequest(claim.getRequestId());
        models.verifyParticipants(claim.getRequestId(), participants);
        var original = settlements.lockEvent(observed.getEventId());
        var marker = scans.lockActivation();
        var scan = scans.lockActiveScan();
        var current = work.lockRequest(claim.getRequestId());
        if (current == null || marker == null || scan == null || !Objects.equals(scan.getId(), claim.getScanId())
                || !Objects.equals(marker.getGeneration(), claim.getGeneration())
                || !Objects.equals(current.getScanId(), claim.getScanId())
                || !Objects.equals(current.getClaimVersion(), claim.getClaimVersion())) {
            return;
        }
        var now = scans.databaseTime();
        if (invocation == null || !Objects.equals(invocation.getEventId(), observed.getEventId())) {
            throw new IllegalStateException("reconciliation invocation identity changed");
        }
        if ("NO_UPSTREAM".equals(invocation.getState())) {
            work.deleteById(current.getId());
            return;
        }
        if (original == null && "RUNNING".equals(invocation.getState())
                && (ownerActive || invocation.getLeaseUntil() != null && now.isBefore(invocation.getLeaseUntil()))) {
            if (ownerActive) {
                invocation.setLastHeartbeatAt(now);
                invocation.setLeaseUntil(now.plusSeconds(30));
                invocations.updateById(invocation);
            }
            work.defer(current.getId(), current.getClaimVersion(), now.plusSeconds(30));
            return;
        }
        var attempt = attempts.latest(invocation.getRequestId());
        if (original == null && attempt != null && "INTENT".equals(attempt.getOutcome())
                && invocation.getLeaseUntil() != null && now.isBefore(invocation.getLeaseUntil().plusSeconds(30))) {
            work.defer(current.getId(), current.getClaimVersion(), invocation.getLeaseUntil().plusSeconds(30));
            return;
        }
        if (original == null && attempt == null) {
            // 与发送意图竞争同一请求锁。确认没有意图后，旧 owner 不能再取得许可。
            invocation.setState("NO_UPSTREAM");
            invocation.setUpdateTime(now);
            invocations.updateById(invocation);
            work.deleteById(current.getId());
            return;
        }
        var existing = cases.lockRequest(current.getTenantId(), current.getRequestId());
        if (original != null && Boolean.TRUE.equals(original.getBudgetApplied())
                && !CostStatus.UNCALCULABLE.name().equals(original.getCostStatus())) {
            if (existing != null && "AUTO".equals(existing.getState())) {
                existing.setState("RESOLVED");
                existing.setVersion(Math.addExact(existing.getVersion(), 1));
                existing.setResolvedAt(now);
                cases.updateById(existing);
            }
            work.deleteById(current.getId());
            return;
        }
        boolean unapplied = original != null && !Boolean.TRUE.equals(original.getBudgetApplied());
        var autoCost = evidence.automatic(attempt);
        boolean canCorrect = autoCost.status() != CostStatus.UNCALCULABLE;
        String state = unapplied || canCorrect ? "AUTO" : attempt == null ? "REVIEW" : "PENDING";
        var row = existing == null ? createCase(current, invocation, original, attempt, state) : existing;
        if (!"FINALIZING".equals(invocation.getState())) {
            invocation.setState("UNKNOWN");
            invocation.setUpdateTime(now);
            invocations.updateById(invocation);
        }
        if ("AUTO".equals(row.getState())) {
            var event = new CostReconciliationEvent();
            event.setEventId("cost-reconciliation-auto-" + row.getId());
            event.setKey(String.valueOf(row.getTenantId()));
            event.setAutomaticCaseId(row.getId());
            event.setAutomaticCaseVersion(row.getVersion());
            if (unapplied) {
                event.setStagedEventId(original.getEventId());
            }
            events.publish(event, row.getTenantId());
            if (work.queueAutomatic(current.getId(), current.getClaimVersion(), now.plusMinutes(5)) != 1) {
                throw new IllegalStateException("reconciliation claim changed before automatic enqueue");
            }
        } else {
            work.deleteById(current.getId());
        }
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
        var task = work.lockRequest(lookup.getRequestId());
        if (task != null && Boolean.TRUE.equals(task.getAutoQueued())) {
            work.deleteById(task.getId());
        }
    }

    /** 自动依赖长期失败时，单个案件不能阻塞同租户的其他人工待办。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void expireAutomatic(CostReconciliationWorkDO expired) {
        tenants.lock(expired.getTenantId());
        var invocation = invocations.lockRequest(expired.getRequestId());
        if (invocation == null) {
            throw new IllegalStateException("missing automatic reconciliation invocation");
        }
        var original = settlements.lockEvent(invocation.getEventId());
        var row = cases.lockRequest(expired.getTenantId(), expired.getRequestId());
        var task = work.lockRequest(expired.getRequestId());
        var now = scans.databaseTime();
        if (task == null || !Boolean.TRUE.equals(task.getAutoQueued()) || task.getNextActionAt().isAfter(now)) {
            return;
        }
        if (row == null) {
            throw new IllegalStateException("missing automatic reconciliation case");
        }
        if ("RESOLVED".equals(row.getState()) || "PENDING".equals(row.getState())) {
            work.deleteById(task.getId());
            return;
        }
        var attempt = row.getAttemptId() == null ? null : attempts.selectById(row.getAttemptId());
        boolean canTransfer = attempt != null && evidence.price(attempt).priced()
                && evidence.automatic(attempt).status() == CostStatus.UNCALCULABLE
                && (original == null || CostStatus.UNCALCULABLE.name().equals(original.getCostStatus()))
                && !Boolean.TRUE.equals(task.getPendingConfirmed());
        if ("AUTO".equals(row.getState())) {
            row.setState(canTransfer ? "PENDING" : "REVIEW");
            row.setVersion(Math.addExact(row.getVersion(), 1));
            cases.updateById(row);
            log.warn("[reconciliation] 自动处理超时，已转交 batchId={} caseId={} state={}",
                    row.getBatchId(), row.getId(), row.getState());
        }
        if (canTransfer) {
            work.deleteById(task.getId());
        } else {
            // 保留待应用的确认金额，直到原事件修复或运维明确处理。
            task.setNextActionAt(now.plusHours(1));
            work.updateById(task);
        }
    }

    private CostReconciliationCaseDO createCase(CostReconciliationWorkDO task, CostBudgetInvocationDO invocation,
                                               CostBudgetSettlementDO original, CostReconciliationAttemptDO attempt,
                                               String state) {
        var batch = tenantBatch(task.getScanId(), task.getTenantId());
        var row = new CostReconciliationCaseDO();
        row.setBatchId(batch.getId());
        row.setTenantId(task.getTenantId());
        row.setRequestId(task.getRequestId());
        row.setEventId(invocation.getEventId());
        row.setApiKeyId(invocation.getApiKeyId());
        row.setSource(invocation.getSource());
        row.setRequestStartedAt(invocation.getRequestStartedAt());
        row.setUserId(task.getUserId());
        row.setAttemptId(attempt == null ? null : attempt.getId());
        row.setState(state);
        row.setVersion(0L);
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
