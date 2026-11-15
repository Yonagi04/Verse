package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.CostReconciliationErrorCode;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.entity.CostReconciliationAdjustmentDO;
import com.yonagi.verse.dao.entity.CostReconciliationAttemptDO;
import com.yonagi.verse.dao.entity.CostReconciliationBatchDO;
import com.yonagi.verse.dao.entity.CostReconciliationCaseDO;
import com.yonagi.verse.dao.entity.CostReconciliationScanDO;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.mapper.CostBudgetSettlementMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAdjustmentMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAttemptMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationBatchMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dto.req.CostReconciliationResolveReqDTO;
import com.yonagi.verse.service.pricing.CostCalculator;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证查询用例的授权与展示契约；Mapper 和限流器仅作为外部边界。 */
class CostReconciliationQueriesTest {
    private static final Long USER_ID = 42L;
    private static final Long TENANT_ID = 7L;
    private static final String CODE = "AbCd1234";
    private final CostReconciliationTracking tracking = mock(CostReconciliationTracking.class);
    private final CostReconciliationBatchMapper batches = mock(CostReconciliationBatchMapper.class);
    private final CostReconciliationScanMapper scans = mock(CostReconciliationScanMapper.class);
    private final CostReconciliationCaseMapper cases = mock(CostReconciliationCaseMapper.class);
    private final CostReconciliationAttemptMapper attempts = mock(CostReconciliationAttemptMapper.class);
    private final CostReconciliationAdjustmentMapper adjustments = mock(CostReconciliationAdjustmentMapper.class);
    private final CostBudgetSettlementMapper settlements = mock(CostBudgetSettlementMapper.class);
    private final LlmServiceMapper models = mock(LlmServiceMapper.class);
    private final TenantAccessPolicy access = mock(TenantAccessPolicy.class);
    private final CostReconciliationCorrectionService corrections = mock(CostReconciliationCorrectionService.class);
    private final RedissonClient redis = mock(RedissonClient.class);
    private final RRateLimiter limiter = mock(RRateLimiter.class);
    private final CostReconciliationQueries service = new CostReconciliationQueries(tracking, batches, scans, cases,
            attempts, adjustments, settlements, models, access, new ReconciliationEvidence(new CostCalculator()),
            corrections, new BudgetPeriodResolver(), redis);
    private final CostReconciliationBatchDO batch = new CostReconciliationBatchDO();
    private final CostReconciliationScanDO scan = new CostReconciliationScanDO();

    @BeforeAll
    static void initializeQueryMetadata() {
        for (Class<?> type : List.of(CostReconciliationBatchDO.class, LlmServiceDO.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),
                    "reconciliation-query-test"), type);
        }
    }

    @BeforeEach
    void setup() {
        when(tracking.enabled()).thenReturn(true);
        when(redis.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire()).thenReturn(true);
        batch.setId(1L);
        batch.setScanId(2L);
        batch.setTenantId(TENANT_ID);
        batch.setCode(CODE);
        scan.setState("SEALED");
        scan.setCutoffAt(LocalDateTime.of(2026, 11, 8, 12, 0));
        when(batches.selectOne(any())).thenReturn(batch);
        when(scans.selectById(2L)).thenReturn(scan);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 40, 41})
    void pageHasBoundedItemsAccurateCountsAndNextCursor(int count) {
        when(cases.selectList(any())).thenReturn(LongStream.rangeClosed(1, count).mapToObj(this::pending).toList());
        when(cases.count(1L, "PENDING")).thenReturn(75L);
        when(cases.count(1L, "RESOLVED")).thenReturn(9L);

        var result = service.page(USER_ID, CODE, CostReconciliationQueries.State.PENDING, 0L);

        assertEquals(Math.min(count, 40), result.items().size());
        assertEquals(count > 40 ? "40" : null, result.nextCursor());
        assertEquals(75L, result.pendingCount());
        assertEquals(9L, result.resolvedCount());
        assertEquals(CODE, result.code());
        assertEquals("2", result.scanId());
        assertEquals("2026-11-08T12:00+08:00", result.scannedAt());
        if (count > 0) {
            assertEquals("1", result.items().getFirst().id());
            assertNull(result.items().getFirst().price());
        }
        verify(access).requireAdmin(USER_ID, TENANT_ID);
        verifyNoInteractions(corrections, models, attempts);
    }

    @Test
    void pageLoadsHistoricalPriceAndCurrentModelNameWithoutInventingResolution() {
        var row = pending(3L);
        row.setAttemptId(5L);
        var attempt = pricedAttempt();
        var model = new LlmServiceDO();
        model.setServiceId(10L);
        model.setModelName("historical-model");
        when(cases.selectList(any())).thenReturn(List.of(row));
        when(attempts.selectBatchIds(anyCollection())).thenReturn(List.of(attempt));
        when(models.selectList(any())).thenReturn(List.of(model));

        var item = service.page(USER_ID, CODE, CostReconciliationQueries.State.PENDING, null).items().getFirst();

        assertEquals("10", item.serviceId());
        assertEquals("historical-model", item.model());
        assertEquals("13", item.price().requestFen());
        assertEquals("REQUEST", item.price().mode());
        assertNull(item.price().inputFen());
        assertNull(item.resolution());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "12345678", "AbCd12345", "AbCd12_4"})
    void malformedBatchCodeDoesNotReachTenantData(String code) {
        assertUnavailable(() -> service.detail(USER_ID, code, 3L));
        verifyNoInteractions(batches, scans, cases, access, corrections);
    }

    @Test
    void disabledOrRateLimitedAccountCannotQueryCases() {
        when(tracking.enabled()).thenReturn(false);
        assertUnavailable(() -> service.detail(USER_ID, CODE, 3L));
        verifyNoInteractions(redis);
        when(tracking.enabled()).thenReturn(true);
        when(limiter.tryAcquire()).thenReturn(false);
        assertUnavailable(() -> service.detail(USER_ID, CODE, 3L));
        verifyNoInteractions(batches, cases, access, corrections);
    }

    @Test
    void missingBatchAndDeniedAdministratorUseTheSamePublicError() {
        when(batches.selectOne(any())).thenReturn(null);
        assertUnavailable(() -> service.detail(USER_ID, CODE, 3L));
        when(batches.selectOne(any())).thenReturn(batch);
        doThrow(new ClientException("tenant membership denied")).when(access).requireAdmin(USER_ID, TENANT_ID);
        assertUnavailable(() -> service.detail(USER_ID, CODE, 3L));
        verifyNoInteractions(cases, corrections);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"DISCOVERING", "DRAINING", "ACTIVATION"})
    void onlySealedScansAreVisible(String state) {
        scan.setState(state);
        assertUnavailable(() -> service.detail(USER_ID, CODE, 3L));
        when(scans.selectById(2L)).thenReturn(null);
        assertUnavailable(() -> service.detail(USER_ID, CODE, 3L));
        verifyNoInteractions(cases, corrections);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTO", "REVIEW"})
    void hiddenCaseCannotBeViewedOrManuallyResolved(String state) {
        var row = pending(3L);
        row.setState(state);
        when(cases.selectOne(any())).thenReturn(row);
        assertUnavailable(() -> service.detail(USER_ID, CODE, 3L));
        var failure = assertThrows(ClientException.class, () -> service.resolve(USER_ID, CODE, 3L, command()));
        assertEquals(CostReconciliationErrorCode.CASE_CHANGED.code(), failure.getErrorCode());
        verifyNoInteractions(corrections);
        assertEquals(state, row.getState());
    }

    @Test
    void unavailableCaseAndDependencyFailureCannotCompleteCorrection() {
        assertUnavailable(() -> service.resolve(USER_ID, CODE, 3L, command()));
        var failure = new IllegalStateException("rate limit dependency unavailable");
        when(limiter.tryAcquire()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.detail(USER_ID, CODE, 3L)));
        verifyNoInteractions(corrections);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detailUsesLatestCorrectionAndPreservesManualOrAutomaticAttribution(boolean automatic) {
        var row = pending(3L);
        row.setState("RESOLVED");
        row.setResolvedAt(scan.getCutoffAt());
        row.setAttemptId(5L);
        row.setReviewRequired(true);
        when(cases.selectOne(any())).thenReturn(row);
        when(attempts.selectById(5L)).thenReturn(pricedAttempt());
        var correction = new CostReconciliationAdjustmentDO();
        correction.setActorId(automatic ? null : USER_ID);
        correction.setCostStatus("CALCULATED");
        correction.setCostFen(new BigDecimal("12.500"));
        correction.setUsageJson(JSON.toJSONString(new ReconciliationEvidence.Tokens(100L, 10L, 20L)));
        when(adjustments.latest(3L)).thenReturn(correction);

        var result = service.detail(USER_ID, CODE, 3L);

        assertTrue(result.reviewRequired());
        assertEquals("12.5", result.resolution().costFen());
        assertEquals(automatic ? "AUTO" : "ADMIN", result.resolution().method());
        assertEquals(100L, result.resolution().inputTokens());
        assertEquals(10L, result.resolution().cachedTokens());
        assertEquals(20L, result.resolution().outputTokens());
        verifyNoInteractions(settlements);
    }

    @Test
    void legacyResolutionUsesOriginalSettlementAndMissingHistoryRemainsAbsent() {
        var row = pending(3L);
        row.setState("RESOLVED");
        row.setResolvedAt(scan.getCutoffAt());
        when(cases.selectOne(any())).thenReturn(row);
        var original = new CostBudgetSettlementDO();
        original.setCostStatus("NOT_CHARGEABLE");
        when(settlements.selectOne(any())).thenReturn(original);
        var result = service.detail(USER_ID, CODE, 3L);
        assertEquals("AUTO", result.resolution().method());
        assertNull(result.resolution().costFen());
        assertNull(result.resolution().inputTokens());
        when(settlements.selectOne(any())).thenReturn(null);
        assertNull(service.detail(USER_ID, CODE, 3L).resolution());
    }

    @Test
    void resolveReturnsFreshCaseAndPropagatesCorrectionRejection() {
        var row = pending(3L);
        when(cases.selectOne(any())).thenReturn(row);
        var command = command();
        doAnswer(call -> { row.setReason("updated-after-correction"); return null; })
                .when(corrections).resolve(row, USER_ID, command);
        assertEquals("updated-after-correction", service.resolve(USER_ID, CODE, 3L, command).reason());
        var denied = new ClientException(CostReconciliationErrorCode.CASE_CHANGED);
        doThrow(denied).when(corrections).resolve(row, USER_ID, command);
        assertSame(denied, assertThrows(ClientException.class,
                () -> service.resolve(USER_ID, CODE, 3L, command)));
    }

    private CostReconciliationCaseDO pending(long id) {
        var row = new CostReconciliationCaseDO();
        row.setId(id);
        row.setBatchId(1L);
        row.setTenantId(TENANT_ID);
        row.setRequestId("request-" + id);
        row.setEventId("event-" + id);
        row.setSource("PLAYGROUND");
        row.setState("PENDING");
        row.setVersion(0L);
        row.setRequestStartedAt(scan.getCutoffAt());
        return row;
    }

    private CostReconciliationAttemptDO pricedAttempt() {
        var attempt = new CostReconciliationAttemptDO();
        attempt.setId(5L);
        attempt.setServiceId(10L);
        attempt.setPricingJson(JSON.toJSONString(new PricingSnapshot(1L, BillingMode.REQUEST, "CNY", null,
                null, null, null, null, new BigDecimal("13.000"), null, null)));
        return attempt;
    }

    private CostReconciliationResolveReqDTO command() {
        return new CostReconciliationResolveReqDTO(CostReconciliationResolveReqDTO.Decision.NO_CHARGE,
                true, 0L, "submission-1", null, null, null);
    }

    private void assertUnavailable(org.junit.jupiter.api.function.Executable action) {
        assertEquals(CostReconciliationErrorCode.BATCH_UNAVAILABLE.code(),
                assertThrows(ClientException.class, action).getErrorCode());
    }
}
