package com.yonagi.verse.service.budget;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.CostReconciliationEvent;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.CostReconciliationErrorCode;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.entity.CostReconciliationAdjustmentDO;
import com.yonagi.verse.dao.entity.CostReconciliationCaseDO;
import com.yonagi.verse.dao.mapper.CostBudgetInvocationMapper;
import com.yonagi.verse.dao.mapper.CostBudgetSettlementMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAdjustmentMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAttemptMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.CostReconciliationResolveReqDTO;
import com.yonagi.verse.service.pricing.CostResult;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** 自动和人工结案共享预算更正、幂等和审计事务。 */
@Service
@RequiredArgsConstructor
public class CostReconciliationCorrectionService {
    private final CostBudgetService budgets;
    private final TenantCostBudgetLedger tenants;
    private final LlmCostBudgetLedger models;
    private final CostBudgetInvocationMapper invocations;
    private final CostBudgetSettlementMapper settlements;
    private final CostReconciliationCaseMapper cases;
    private final CostReconciliationAttemptMapper attempts;
    private final CostReconciliationAdjustmentMapper adjustments;
    private final UserMapper users;
    private final TenantAccessPolicy access;
    private final LlmCostBudgetConfigurationService permissions;
    private final ReconciliationEvidence evidence;
    private final ReliableDomainEventPublisher events;
    private final CostReconciliationSignals signals;
    private final BudgetPeriodResolver time;

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public CostReconciliationAdjustmentDO resolve(CostReconciliationCaseDO lookup, Long actorId,
                                                  CostReconciliationResolveReqDTO command) {
        return settle(lookup, actorId, command, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public CostReconciliationAdjustmentDO automatic(CostReconciliationCaseDO lookup, long expectedVersion) {
        return settle(lookup, null, null, expectedVersion);
    }

    public static boolean acceptsAutomatic(CostReconciliationCaseDO row, long expectedVersion) {
        return "AUTO".equals(row.getState()) && row.getVersion() == expectedVersion
                || "REVIEW".equals(row.getState()) && row.getAttemptId() != null && row.getVersion() - 1 == expectedVersion;
    }

    private CostReconciliationAdjustmentDO settle(CostReconciliationCaseDO lookup, Long actorId,
                                                  CostReconciliationResolveReqDTO command, Long automaticVersion) {
        Long tenantId = lookup.getTenantId();
        if (actorId != null) {
            lockPermission(tenantId, lookup.getUserId(), actorId);
        } else {
            users.lockResourceOwner(lookup.getUserId());
        }
        var attempt = attempts.selectById(lookup.getAttemptId());
        if (attempt == null || !Objects.equals(attempt.getRequestId(), lookup.getRequestId())) {
            throw invalidEvidence();
        }
        var tenant = tenants.lock(tenantId);
        var key = lookup.getApiKeyId() == null ? null : budgets.lockKey(tenantId, lookup.getApiKeyId());
        var participants = models.lockForRequest(tenantId, lookup.getRequestId(), attempt.getServiceId());
        var invocation = invocations.lockRequest(lookup.getRequestId());
        models.verifyParticipants(lookup.getRequestId(), participants);
        var original = settlements.lockEvent(lookup.getEventId());
        var current = cases.lockInBatch(lookup.getBatchId(), lookup.getId());
        if (current == null || !Objects.equals(current.getTenantId(), tenantId)) {
            throw unavailable();
        }
        if (automaticVersion != null && !acceptsAutomatic(current, automaticVersion)) {
            return null;
        }
        if (invocation == null || !Objects.equals(invocation.getTenantId(), tenantId)
                || !Objects.equals(invocation.getEventId(), current.getEventId())
                || !Objects.equals(invocation.getApiKeyId(), current.getApiKeyId())
                || !Objects.equals(invocation.getSource(), current.getSource())
                || !Objects.equals(invocation.getRequestStartedAt(), current.getRequestStartedAt())
                || original != null && (!Objects.equals(original.getTenantId(), tenantId)
                || !Objects.equals(original.getRequestId(), current.getRequestId())
                || !Objects.equals(original.getSource(), current.getSource()))) {
            throw new CostBudgetSnapshot.ConflictException();
        }
        String submissionKey = command == null ? "automatic" : command.submissionKey();
        String fingerprint = DigestUtil.sha256Hex(command == null ? "automatic" : JSON.toJSONString(command));
        var replay = adjustments.selectOne(Wrappers.lambdaQuery(CostReconciliationAdjustmentDO.class)
                .eq(CostReconciliationAdjustmentDO::getCaseId, current.getId())
                .eq(CostReconciliationAdjustmentDO::getSubmissionKey, submissionKey));
        if (replay != null) {
            if (!Objects.equals(replay.getActorId(), actorId) || !fingerprint.equals(replay.getFingerprint())) {
                throw changed();
            }
            return replay;
        }
        if (!Objects.equals(current.getAttemptId(), lookup.getAttemptId())
                || command != null && (!"PENDING".equals(current.getState()) || !command.version().equals(current.getVersion()))) {
            throw changed();
        }
        // 原终态也持有请求锁，获取最新证据后再决定是否可自动确认。
        attempt = attempts.selectById(current.getAttemptId());
        var tokens = command == null ? JSON.parseObject(attempt.getUsageJson(), ReconciliationEvidence.Tokens.class)
                : new ReconciliationEvidence.Tokens(command.inputTokens(), command.cachedTokens(), command.outputTokens());
        var result = command == null ? evidence.automatic(attempt) : manual(attempt, command, tokens);
        if (result.status() == CostStatus.UNCALCULABLE || result.status() == CostStatus.UNPRICED && command != null) {
            throw invalidEvidence();
        }
        var previous = adjustments.latest(current.getId());
        var before = previous == null
                ? original != null && Boolean.TRUE.equals(original.getBudgetApplied()) ? original : null
                : contribution(current, previous.getServiceId(), previous.getCostStatus(), previous.getCostFen());
        var after = contribution(current, attempt.getServiceId(), result.status().name(), result.estimatedCostFen());
        budgets.adjustContribution(tenant, key, participants, before, after);

        var saved = new CostReconciliationAdjustmentDO();
        saved.setCaseId(current.getId());
        saved.setAttemptId(attempt.getId());
        saved.setServiceId(attempt.getServiceId());
        saved.setOperation(attempt.getOperation());
        saved.setVersion(Math.addExact(current.getVersion(), 1));
        saved.setSubmissionKey(submissionKey);
        saved.setFingerprint(fingerprint);
        saved.setActorId(actorId);
        saved.setCostStatus(after.getCostStatus());
        saved.setCostFen(after.getEstimatedCostFen());
        if (tokens != null && tokens.valid()) {
            saved.setUsageJson(JSON.toJSONString(tokens));
        }
        saved.setCreatedAt(time.local(time.now()));
        adjustments.insert(saved);

        current.setState("RESOLVED");
        current.setVersion(saved.getVersion());
        current.setResolvedAt(saved.getCreatedAt());
        cases.updateById(current);
        signals.batchChanged(current);
        invocation.setState("SETTLED");
        invocation.setReconciliationDisposition("CASE");
        invocation.setUpdateTime(saved.getCreatedAt());
        invocations.updateById(invocation);
        var event = new CostReconciliationEvent();
        event.setEventId("cost-reconciliation-adjustment-" + saved.getId());
        event.setKey(String.valueOf(tenantId));
        event.setAdjustmentId(saved.getId());
        events.publish(event, tenantId);
        return saved;
    }

    private void lockPermission(Long tenantId, Long ownerId, Long actorId) {
        try {
            access.lockActiveTenantForShare(tenantId);
            var ids = new TreeSet<>(List.of(actorId, ownerId));
            for (Long userId : ids) {
                users.lockResourceOwner(userId);
            }
            permissions.requireAdmin(tenantId, actorId);
        } catch (ClientException denied) {
            throw unavailable();
        }
    }

    private CostResult manual(com.yonagi.verse.dao.entity.CostReconciliationAttemptDO attempt,
                              CostReconciliationResolveReqDTO command, ReconciliationEvidence.Tokens tokens) {
        if (!Boolean.TRUE.equals(command.evidenceConfirmed())) {
            throw invalidEvidence();
        }
        var price = evidence.price(attempt);
        boolean anyTokens = tokens.input() != null || tokens.cached() != null || tokens.output() != null;
        if (command.decision() == CostReconciliationResolveReqDTO.Decision.NO_CHARGE) {
            if (anyTokens) {
                throw invalidEvidence();
            }
            return CostResult.of(CostStatus.NOT_CHARGEABLE);
        }
        if (!price.priced() || (price.billingMode() == BillingMode.TOKEN ? !tokens.valid() : anyTokens)) {
            throw invalidEvidence();
        }
        return evidence.charge(price, tokens);
    }

    private CostBudgetSettlementDO contribution(CostReconciliationCaseDO row, Long serviceId, String status,
                                                 java.math.BigDecimal amount) {
        var contribution = new CostBudgetSettlementDO();
        contribution.setServiceId(serviceId);
        contribution.setRequestStartedAt(row.getRequestStartedAt());
        contribution.setCostStatus(status);
        contribution.setEstimatedCostFen(amount);
        return contribution;
    }

    private ClientException changed() {
        return new ClientException(CostReconciliationErrorCode.CASE_CHANGED);
    }

    private ClientException invalidEvidence() {
        return new ClientException(CostReconciliationErrorCode.INVALID_EVIDENCE);
    }

    static ClientException unavailable() {
        return new ClientException(CostReconciliationErrorCode.BATCH_UNAVAILABLE);
    }
}
