package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.CostReconciliationEvent;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.entity.CostReconciliationAttemptDO;
import com.yonagi.verse.dao.entity.CostReconciliationAdjustmentDO;
import com.yonagi.verse.dao.entity.CostReconciliationCaseDO;
import com.yonagi.verse.dao.entity.CostReconciliationWorkDO;
import com.yonagi.verse.dao.mapper.CostBudgetInvocationMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAdjustmentMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAttemptMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationWorkMapper;
import com.yonagi.verse.service.forward.UpstreamExecutionOutcome;
import com.yonagi.verse.service.pricing.CostResult;
import com.yonagi.verse.service.pricing.PricingResolver;
import com.yonagi.verse.service.usage.UsageBreakdown;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 持久化恢复所需的最小证据，禁止写入提示词、凭据或上游正文。 */
@Component
@RequiredArgsConstructor
public class CostReconciliationTracking {
    private final CostReconciliationScanMapper scans;
    private final CostReconciliationWorkMapper work;
    private final CostReconciliationAttemptMapper attempts;
    private final CostReconciliationCaseMapper cases;
    private final CostReconciliationAdjustmentMapper adjustments;
    private final CostBudgetInvocationMapper invocations;
    private final PricingResolver prices;
    private final ReconciliationEvidence evidence;
    private final BudgetPeriodResolver time;
    private final ReliableDomainEventPublisher events;

    @Value("${verse.llm.cost-reconciliation.capture-enabled:false}")
    private boolean captureEnabled;

    public boolean enabled() {
        return generation() > 0;
    }

    private boolean captures() {
        // 启用后读取和幂等保护绑定数据库代次，运维暂停扫描不切回旧账。
        return captureEnabled || enabled();
    }

    private long generation() {
        Long generation = scans.activationGeneration();
        if (generation == null) {
            throw new IllegalStateException("missing reconciliation activation marker");
        }
        return generation;
    }

    void register(CostBudgetInvocationDO invocation, Long userId) {
        long generation = generation();
        if (!captureEnabled && generation == 0) {
            return;
        }
        var row = new CostReconciliationWorkDO();
        row.setRequestId(invocation.getRequestId());
        row.setTenantId(invocation.getTenantId());
        row.setUserId(Objects.requireNonNull(userId, "invocation user"));
        row.setGeneration(generation);
        row.setNextActionAt(invocation.getLeaseUntil());
        row.setRequestStartedAt(invocation.getRequestStartedAt());
        work.insert(row);
    }

    /** 在供应商传输之前提交。原调用锁与恢复撤销发送资格使用同一行。 */
    @Transactional(rollbackFor = Exception.class)
    public Long intent(Long tenantId, String requestId, Long serviceId, String provider,
                       ModelOperation operation) {
        if (!captures()) {
            return null;
        }
        var observed = invocations.selectOne(Wrappers.lambdaQuery(CostBudgetInvocationDO.class)
                .eq(CostBudgetInvocationDO::getRequestId, requestId));
        if (observed == null || !Objects.equals(observed.getTenantId(), tenantId)) {
            throw new CostBudgetUnavailableException();
        }
        var started = observed.getRequestStartedAt().atZone(BudgetPeriodResolver.ZONE).toInstant();
        var price = prices.resolve(tenantId, serviceId, started);
        var invocation = invocations.lockRequest(requestId);
        if (invocation == null || !Objects.equals(observed.getEventId(), invocation.getEventId())) {
            throw new CostBudgetUnavailableException();
        }
        var task = work.lockRequest(requestId);
        if (!Objects.equals(tenantId, invocation.getTenantId()) || !"RUNNING".equals(invocation.getState()) || task == null) {
            throw new CostBudgetUnavailableException();
        }
        var previous = attempts.latest(requestId);
        if (previous != null && !"NOT_SENT".equals(previous.getOutcome()) && !"REJECTED".equals(previous.getOutcome())) {
            throw new CostBudgetUnavailableException();
        }
        var row = new CostReconciliationAttemptDO();
        row.setRequestId(requestId);
        row.setAttemptNo(previous == null ? 1 : Math.addExact(previous.getAttemptNo(), 1));
        row.setServiceId(serviceId);
        row.setProvider(provider);
        row.setOperation(operation.name());
        row.setPricingJson(JSON.toJSONString(price));
        row.setOutcome("INTENT");
        attempts.insert(row);
        return row.getId();
    }

    public void recordRejection(Long attemptId, UpstreamExecutionOutcome outcome) {
        // 影子阶段兼容旧重试，启用后通用 HTTP 拒绝只说明传输结果，不能证明费用为零。
        if (attemptId != null && (outcome == UpstreamExecutionOutcome.NOT_SENT
                || outcome == UpstreamExecutionOutcome.REJECTED && !enabled())) {
            attempts.update(Wrappers.lambdaUpdate(CostReconciliationAttemptDO.class)
                    .eq(CostReconciliationAttemptDO::getId, attemptId)
                    .eq(CostReconciliationAttemptDO::getOutcome, "INTENT")
                    .set(CostReconciliationAttemptDO::getOutcome, outcome.name()));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeAttempt(Long attemptId, String requestId, UsageBreakdown usage) {
        if (attemptId == null) {
            return;
        }
        var invocation = invocations.lockRequest(requestId);
        if (invocation == null) {
            return;
        }
        var attempt = attempts.selectById(attemptId);
        if (attempt == null || !Objects.equals(requestId, attempt.getRequestId())
                || !("INTENT".equals(attempt.getOutcome()) || "COMPLETED".equals(attempt.getOutcome()))) {
            return;
        }
        var proof = new TokenUsageEvent();
        proof.setExecutionOutcome(UpstreamExecutionOutcome.COMPLETED);
        proof.setStatus("SUCCESS");
        proof.setUsageSource(TokenUsageEvent.SOURCE_EXACT);
        proof.setNormalizedUsage(usage);
        evidence.recordTerminal(attempt, proof, true);
        attempts.updateById(attempt);
        advanceCaseWithProof(invocation, attempt);
    }

    private void advanceCaseWithProof(CostBudgetInvocationDO invocation, CostReconciliationAttemptDO attempt) {
        var result = evidence.automatic(attempt);
        if (result.status() != CostStatus.CALCULATED || !enabled()) {
            return;
        }
        var row = cases.lockRequest(invocation.getTenantId(), invocation.getRequestId());
        if (row == null || !Objects.equals(row.getAttemptId(), attempt.getId())) {
            return;
        }
        if ("PENDING".equals(row.getState())) {
            queueLateAutomatic(invocation, row);
        } else if ("RESOLVED".equals(row.getState()) && !Boolean.TRUE.equals(row.getReviewRequired())) {
            var adjustment = adjustments.latest(row.getId());
            if (adjustment != null && contradicts(attempt, result, adjustment)) {
                flagReview(row);
            }
        }
    }

    void queueLateAutomatic(CostBudgetInvocationDO invocation, CostReconciliationCaseDO row) {
        row.setState("AUTO");
        row.setVersion(Math.addExact(row.getVersion(), 1));
        cases.updateById(row);
        var deadline = time.local(time.now()).plusMinutes(5);
        var task = work.lockRequest(row.getRequestId());
        if (task == null) {
            task = new CostReconciliationWorkDO();
            task.setRequestId(row.getRequestId());
            task.setTenantId(row.getTenantId());
            task.setUserId(row.getUserId());
            task.setGeneration(generation());
            task.setRequestStartedAt(invocation.getRequestStartedAt());
            task.setAutoQueued(true);
            task.setNextActionAt(deadline);
            work.insert(task);
        } else if (work.queueAutomatic(task.getId(), task.getClaimVersion(), deadline) != 1) {
            throw new IllegalStateException("reconciliation work changed before late evidence enqueue");
        }
        var event = new CostReconciliationEvent();
        event.setEventId("cost-reconciliation-late-" + row.getId() + "-" + row.getVersion());
        event.setKey(String.valueOf(row.getTenantId()));
        event.setAutomaticCaseId(row.getId());
        event.setAutomaticCaseVersion(row.getVersion());
        events.publish(event, row.getTenantId());
    }

    private boolean contradicts(CostReconciliationAttemptDO attempt, CostResult result,
                                CostReconciliationAdjustmentDO adjustment) {
        return !result.status().name().equals(adjustment.getCostStatus())
                || adjustment.getCostFen() == null || result.estimatedCostFen().compareTo(adjustment.getCostFen()) != 0
                || !Objects.equals(attempt.getServiceId(), adjustment.getServiceId())
                || attempt.getUsageJson() != null && adjustment.getUsageJson() != null
                && !Objects.equals(JSON.parseObject(attempt.getUsageJson(), ReconciliationEvidence.Tokens.class),
                JSON.parseObject(adjustment.getUsageJson(), ReconciliationEvidence.Tokens.class));
    }

    void prepareTerminal(TokenUsageEvent event, CostBudgetInvocationDO invocation, CostBudgetSettlementDO original) {
        if (invocation == null || !captures()) {
            return;
        }
        var task = work.lockRequest(invocation.getRequestId());
        var trackedCase = task == null ? findCase(invocation) : null;
        if (task == null && trackedCase == null) {
            return;
        }
        Long originalUser = task == null ? trackedCase.getUserId() : task.getUserId();
        if (!Objects.equals(invocation.getTenantId(), event.getTenantId())
                || !Objects.equals(invocation.getApiKeyId(), event.getApiKeyId())
                || !Objects.equals(invocation.getSource(), event.getSource())
                || event.getRequestStartedAt() == null
                || invocation.getRequestStartedAt().atZone(BudgetPeriodResolver.ZONE).toInstant().toEpochMilli()
                != event.getRequestStartedAt().toEpochMilli()
                || !Objects.equals(originalUser, event.getUserId())) {
            throw new CostBudgetSnapshot.ConflictException();
        }
        var attempt = attempts.latest(invocation.getRequestId());
        if (attempt != null && Objects.equals(attempt.getServiceId(), event.getServiceId())
                && Objects.equals(attempt.getOperation(), event.getOperation())) {
            evidence.recordTerminal(attempt, event);
            attempts.updateById(attempt);
            if (trackedCase != null || original != null) {
                advanceCaseWithProof(invocation, attempt);
            }
        } else {
            attempt = null;
        }
        if (task != null && task.getGeneration() == 0) {
            return;
        }
        if (original != null) {
            var stored = JSON.parseObject(original.getEventPayloadJson(), TokenUsageEvent.class);
            event.setCostResult(stored.getCostResult());
            event.setPricingSnapshot(stored.getPricingSnapshot());
            return;
        }
        event.setPricingSnapshot(attempt == null ? null : evidence.price(attempt));
        event.setCostResult(attempt == null ? CostResult.of(CostStatus.UNCALCULABLE) : evidence.automatic(attempt));
    }

    void staged(CostBudgetInvocationDO invocation, TokenUsageEvent event) {
        if (invocation == null || !captures()) {
            return;
        }
        var row = work.lockRequest(invocation.getRequestId());
        if (row != null) {
            // 正常在线应用优先，短暂暂存不应被误识别为故障。
            if (!Boolean.TRUE.equals(row.getAutoQueued())) {
                row.setNextActionAt(time.local(time.now()).plusSeconds(5));
            }
            row.setPendingConfirmed(row.getGeneration() > 0 && event.getCostResult().status() == CostStatus.CALCULATED
                    && !hasAdjustment(invocation));
            work.updateById(row);
        }
    }

    void applied(CostBudgetInvocationDO invocation, CostStatus status) {
        if (invocation == null || !captures()) {
            return;
        }
        var row = work.lockRequest(invocation.getRequestId());
        if (row == null) {
            return;
        }
        if (row.getGeneration() == 0 || status != CostStatus.UNCALCULABLE && row.getScanId() == null) {
            work.deleteById(row.getId());
        } else {
            row.setPendingConfirmed(false);
            work.updateById(row);
        }
    }

    void abandoned(CostBudgetInvocationDO invocation) {
        if (captures()) {
            var row = work.lockRequest(invocation.getRequestId());
            if (row != null) {
                work.deleteById(row.getId());
            }
        }
    }

    CostReconciliationCaseDO findCase(CostBudgetInvocationDO invocation) {
        if (invocation == null || !enabled()) {
            return null;
        }
        return cases.selectOne(Wrappers.lambdaQuery(CostReconciliationCaseDO.class)
                .eq(CostReconciliationCaseDO::getTenantId, invocation.getTenantId())
                .eq(CostReconciliationCaseDO::getRequestId, invocation.getRequestId()));
    }

    boolean hasAdjustment(CostBudgetInvocationDO invocation) {
        var current = findCase(invocation);
        return current != null && adjustments.exists(Wrappers.lambdaQuery(CostReconciliationAdjustmentDO.class)
                .eq(CostReconciliationAdjustmentDO::getCaseId, current.getId()));
    }

    /** 调用方已锁原请求，防止旧重放事件跨过后到证据的案件版本。 */
    boolean acceptsReplay(CostBudgetInvocationDO invocation, Long caseId, long expectedVersion) {
        var row = findCase(invocation);
        return row != null && Objects.equals(row.getId(), caseId)
                && CostReconciliationCorrectionService.acceptsAutomatic(row, expectedVersion);
    }

    /** 同一已更正请求迟到的矛盾证据保留在尝试和复核状态中，原快照及金额继续有效。 */
    boolean retainCorrectedConflict(CostBudgetInvocationDO invocation) {
        if (!hasAdjustment(invocation)) {
            return false;
        }
        flagReview(cases.lockRequest(invocation.getTenantId(), invocation.getRequestId()));
        return true;
    }

    boolean retainsVerifiedAutomaticEvidence(CostBudgetInvocationDO invocation, CostBudgetSettlementDO original) {
        if (!CostStatus.UNCALCULABLE.name().equals(original.getCostStatus())) {
            return false;
        }
        var row = findCase(invocation);
        return row != null && "AUTO".equals(row.getState()) && row.getAttemptId() != null
                && evidence.automatic(attempts.selectById(row.getAttemptId())).status() == CostStatus.CALCULATED;
    }

    boolean activeRequest(CostBudgetInvocationDO invocation) {
        if (invocation == null || !enabled()) {
            return false;
        }
        var task = work.lockRequest(invocation.getRequestId());
        return task != null && task.getGeneration() > 0 || findCase(invocation) != null;
    }

    void resolveFromOriginal(CostBudgetInvocationDO invocation, CostStatus status) {
        if (status == CostStatus.UNCALCULABLE || findCase(invocation) == null) {
            return;
        }
        var row = cases.lockRequest(invocation.getTenantId(), invocation.getRequestId());
        if ("PENDING".equals(row.getState()) || "AUTO".equals(row.getState()) || "REVIEW".equals(row.getState())) {
            row.setState("RESOLVED");
            row.setVersion(Math.addExact(row.getVersion(), 1));
            row.setResolvedAt(time.local(time.now()));
            cases.updateById(row);
        }
    }

    void reviewLateConflict(CostBudgetInvocationDO invocation, CostBudgetSettlementDO original) {
        if (CostStatus.UNCALCULABLE.name().equals(original.getCostStatus()) || findCase(invocation) == null) {
            return;
        }
        var row = cases.lockRequest(invocation.getTenantId(), invocation.getRequestId());
        var correction = adjustments.latest(row.getId());
        if (correction == null || Boolean.TRUE.equals(row.getReviewRequired())) {
            return;
        }
        boolean sameAmount = original.getEstimatedCostFen() == null ? correction.getCostFen() == null
                : correction.getCostFen() != null && original.getEstimatedCostFen().compareTo(correction.getCostFen()) == 0;
        if (!sameAmount || !Objects.equals(original.getCostStatus(), correction.getCostStatus())
                || !Objects.equals(original.getServiceId(), correction.getServiceId())) {
            flagReview(row);
        }
    }

    private void flagReview(CostReconciliationCaseDO row) {
        if (!Boolean.TRUE.equals(row.getReviewRequired())) {
            row.setReviewRequired(true);
            row.setVersion(Math.addExact(row.getVersion(), 1));
            cases.updateById(row);
        }
    }
}
