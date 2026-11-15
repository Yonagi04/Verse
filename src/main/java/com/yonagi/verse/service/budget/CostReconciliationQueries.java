package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.CostReconciliationErrorCode;
import com.yonagi.verse.dao.entity.CostReconciliationAttemptDO;
import com.yonagi.verse.dao.entity.CostReconciliationBatchDO;
import com.yonagi.verse.dao.entity.CostReconciliationCaseDO;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.mapper.CostBudgetSettlementMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAdjustmentMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAttemptMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationBatchMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dto.req.CostReconciliationResolveReqDTO;
import com.yonagi.verse.dto.resp.CostReconciliationRespDTO;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 批次、案件、原账读取主库，不缓存成员资格和补账结果。 */
@Service
@RequiredArgsConstructor
public class CostReconciliationQueries {
    private static final int PAGE_SIZE = 40;
    private final CostReconciliationTracking tracking;
    private final CostReconciliationBatchMapper batches;
    private final CostReconciliationScanMapper scans;
    private final CostReconciliationCaseMapper cases;
    private final CostReconciliationAttemptMapper attempts;
    private final CostReconciliationAdjustmentMapper adjustments;
    private final CostBudgetSettlementMapper settlements;
    private final LlmServiceMapper services;
    private final TenantAccessPolicy access;
    private final ReconciliationEvidence evidence;
    private final CostReconciliationCorrectionService corrections;
    private final BudgetPeriodResolver time;
    private final RedissonClient redis;

    public enum State { PENDING, RESOLVED }

    public CostReconciliationRespDTO.Batch page(Long userId, String code, State state, Long after) {
        var batch = requireBatch(userId, code);
        var scan = scans.selectById(batch.getScanId());
        var rows = cases.selectList(Wrappers.lambdaQuery(CostReconciliationCaseDO.class)
                .eq(CostReconciliationCaseDO::getBatchId, batch.getId()).eq(CostReconciliationCaseDO::getTenantId, batch.getTenantId())
                .eq(CostReconciliationCaseDO::getState, state.name()).gt(after != null, CostReconciliationCaseDO::getId, after)
                .orderByAsc(CostReconciliationCaseDO::getId).last("LIMIT 41"));
        var page = rows.stream().limit(PAGE_SIZE).toList();
        var indexed = loadAttempts(page.stream().map(CostReconciliationCaseDO::getAttemptId).filter(Objects::nonNull).toList());
        var names = names(batch.getTenantId(), indexed.values().stream().map(CostReconciliationAttemptDO::getServiceId).toList());
        return new CostReconciliationRespDTO.Batch(batch.getCode(), String.valueOf(batch.getScanId()), time.iso(scan.getCutoffAt()),
                cases.count(batch.getId(), "PENDING"), cases.count(batch.getId(), "RESOLVED"),
                rows.size() > PAGE_SIZE ? String.valueOf(page.getLast().getId()) : null,
                page.stream().map(row -> summary(row, row.getAttemptId() == null ? null : indexed.get(row.getAttemptId()),
                        names, null)).toList());
    }

    public CostReconciliationRespDTO.Case detail(Long userId, String code, Long id) {
        var batch = requireBatch(userId, code);
        var row = requireCase(batch, id);
        if (!visible(row)) {
            throw CostReconciliationCorrectionService.unavailable();
        }
        return detail(row);
    }

    public CostReconciliationRespDTO.Case resolve(Long userId, String code, Long id, CostReconciliationResolveReqDTO request) {
        var batch = requireBatch(userId, code);
        var row = requireCase(batch, id);
        if (!visible(row)) {
            throw new ClientException(CostReconciliationErrorCode.CASE_CHANGED);
        }
        corrections.resolve(row, userId, request);
        return detail(requireCase(batch, id));
    }

    private CostReconciliationRespDTO.Case detail(CostReconciliationCaseDO row) {
        var attempt = row.getAttemptId() == null ? null : attempts.selectById(row.getAttemptId());
        var names = attempt == null ? Map.<Long, String>of() : names(row.getTenantId(), List.of(attempt.getServiceId()));
        return summary(row, attempt, names, resolution(row));
    }

    private CostReconciliationBatchDO requireBatch(Long userId, String code) {
        if (!tracking.enabled()) {
            throw CostReconciliationCorrectionService.unavailable();
        }
        var limiter = redis.getRateLimiter("verse:cost-reconciliation:account:" + userId);
        limiter.trySetRate(RateType.OVERALL, 120, 1, RateIntervalUnit.MINUTES);
        limiter.expire(Duration.ofMinutes(10));
        if (!limiter.tryAcquire()) {
            throw CostReconciliationCorrectionService.unavailable();
        }
        if (code == null || !code.matches("[A-Za-z][A-Za-z0-9]{7}")) {
            throw CostReconciliationCorrectionService.unavailable();
        }
        var batch = batches.selectOne(Wrappers.lambdaQuery(CostReconciliationBatchDO.class)
                .select(CostReconciliationBatchDO::getId, CostReconciliationBatchDO::getScanId,
                        CostReconciliationBatchDO::getTenantId, CostReconciliationBatchDO::getCode)
                .eq(CostReconciliationBatchDO::getCode, code));
        if (batch == null) {
            throw CostReconciliationCorrectionService.unavailable();
        }
        try {
            access.requireAdmin(userId, batch.getTenantId());
        } catch (ClientException denied) {
            throw new ClientException(CostReconciliationErrorCode.BATCH_UNAVAILABLE);
        }
        var scan = scans.selectById(batch.getScanId());
        if (scan == null || !"SEALED".equals(scan.getState())) {
            throw CostReconciliationCorrectionService.unavailable();
        }
        return batch;
    }

    private CostReconciliationCaseDO requireCase(CostReconciliationBatchDO batch, Long id) {
        var row = cases.selectOne(Wrappers.lambdaQuery(CostReconciliationCaseDO.class)
                .eq(CostReconciliationCaseDO::getBatchId, batch.getId()).eq(CostReconciliationCaseDO::getTenantId, batch.getTenantId())
                .eq(CostReconciliationCaseDO::getId, id));
        if (row == null) {
            throw CostReconciliationCorrectionService.unavailable();
        }
        return row;
    }

    private boolean visible(CostReconciliationCaseDO row) {
        return "PENDING".equals(row.getState()) || "RESOLVED".equals(row.getState());
    }

    private Map<Long, CostReconciliationAttemptDO> loadAttempts(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return attempts.selectBatchIds(ids).stream().collect(Collectors.toMap(CostReconciliationAttemptDO::getId, Function.identity()));
    }

    private Map<Long, String> names(Long tenantId, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return services.selectList(Wrappers.lambdaQuery(LlmServiceDO.class)
                .select(LlmServiceDO::getServiceId, LlmServiceDO::getModelName)
                .eq(LlmServiceDO::getTenantId, tenantId).in(LlmServiceDO::getServiceId, ids))
                .stream().collect(Collectors.toMap(LlmServiceDO::getServiceId, LlmServiceDO::getModelName));
    }

    private CostReconciliationRespDTO.Case summary(CostReconciliationCaseDO row, CostReconciliationAttemptDO attempt,
                                                    Map<Long, String> names, CostReconciliationRespDTO.Resolution resolution) {
        var price = attempt == null ? null : evidence.price(attempt);
        var serviceId = attempt == null ? null : attempt.getServiceId();
        var shownPrice = price == null || !price.priced() ? null
                : new CostReconciliationRespDTO.Price(price.billingMode().name(), amount(price.requestPriceFen()),
                amount(price.cacheMissInputPriceFen()), amount(price.cacheHitInputPriceFen()), amount(price.outputPriceFen()));
        return new CostReconciliationRespDTO.Case(String.valueOf(row.getId()), row.getRequestId(),
                serviceId == null ? null : String.valueOf(serviceId), serviceId == null ? null : names.get(serviceId),
                row.getSource(), time.iso(row.getRequestStartedAt()), row.getState(), row.getReason(), row.getVersion(),
                Boolean.TRUE.equals(row.getReviewRequired()), shownPrice, resolution);
    }

    private CostReconciliationRespDTO.Resolution resolution(CostReconciliationCaseDO row) {
        if (!"RESOLVED".equals(row.getState())) {
            return null;
        }
        var adjusted = adjustments.latest(row.getId());
        if (adjusted != null) {
            var tokens = JSON.parseObject(adjusted.getUsageJson(), ReconciliationEvidence.Tokens.class);
            return new CostReconciliationRespDTO.Resolution(adjusted.getCostStatus(), amount(adjusted.getCostFen()),
                    adjusted.getActorId() == null ? "AUTO" : "ADMIN", time.iso(row.getResolvedAt()),
                    tokens == null ? null : tokens.input(), tokens == null ? null : tokens.cached(), tokens == null ? null : tokens.output());
        }
        var original = settlements.selectOne(Wrappers.lambdaQuery(com.yonagi.verse.dao.entity.CostBudgetSettlementDO.class)
                .eq(com.yonagi.verse.dao.entity.CostBudgetSettlementDO::getEventId, row.getEventId()));
        return original == null ? null : new CostReconciliationRespDTO.Resolution(original.getCostStatus(),
                amount(original.getEstimatedCostFen()), "AUTO", time.iso(row.getResolvedAt()), null, null, null);
    }

    private String amount(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
