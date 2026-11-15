package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.CostReconciliationEvent;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.CostReconciliationErrorCode;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import com.yonagi.verse.dao.entity.CostBudgetSettlementDO;
import com.yonagi.verse.dao.entity.CostReconciliationAdjustmentDO;
import com.yonagi.verse.dao.entity.CostReconciliationAttemptDO;
import com.yonagi.verse.dao.entity.CostReconciliationCaseDO;
import com.yonagi.verse.dao.mapper.CostBudgetInvocationMapper;
import com.yonagi.verse.dao.mapper.CostBudgetSettlementMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAdjustmentMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationAttemptMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.CostReconciliationResolveReqDTO;
import com.yonagi.verse.service.pricing.CostCalculator;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 结案业务状态与费用依据；锁和提交原子性由环境验证。 */
class CostReconciliationCorrectionServiceDecisionTest {
    private static final Long TENANT_ID = 7L;
    private static final Long ACTOR_ID = 42L;
    private static final long VERSION = 4L;
    private final CostBudgetService budgets = mock(CostBudgetService.class);
    private final TenantCostBudgetLedger tenants = mock(TenantCostBudgetLedger.class);
    private final LlmCostBudgetLedger models = mock(LlmCostBudgetLedger.class);
    private final CostBudgetInvocationMapper invocations = mock(CostBudgetInvocationMapper.class);
    private final CostBudgetSettlementMapper settlements = mock(CostBudgetSettlementMapper.class);
    private final CostReconciliationCaseMapper cases = mock(CostReconciliationCaseMapper.class);
    private final CostReconciliationAttemptMapper attempts = mock(CostReconciliationAttemptMapper.class);
    private final CostReconciliationAdjustmentMapper adjustments = mock(CostReconciliationAdjustmentMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final TenantAccessPolicy access = mock(TenantAccessPolicy.class);
    private final LlmCostBudgetConfigurationService permissions = mock(LlmCostBudgetConfigurationService.class);
    private final ReliableDomainEventPublisher events = mock(ReliableDomainEventPublisher.class);
    private final CostReconciliationSignals signals = mock(CostReconciliationSignals.class);
    private final BudgetPeriodResolver time = new BudgetPeriodResolver(
            Clock.fixed(Instant.parse("2026-11-08T07:00:00Z"), ZoneOffset.UTC));
    private final CostReconciliationCorrectionService service = new CostReconciliationCorrectionService(budgets,
            tenants, models, invocations, settlements, cases, attempts, adjustments, users, access, permissions,
            new ReconciliationEvidence(new CostCalculator()), events, signals, time);
    private final CostReconciliationCaseDO row = new CostReconciliationCaseDO();
    private final CostReconciliationAttemptDO attempt = new CostReconciliationAttemptDO();
    private final CostBudgetInvocationDO invocation = new CostBudgetInvocationDO();

    @BeforeEach
    void setup() {
        row.setId(3L);
        row.setBatchId(8L);
        row.setTenantId(TENANT_ID);
        row.setUserId(9L);
        row.setRequestId("request");
        row.setEventId("event");
        row.setSource("PLAYGROUND");
        row.setRequestStartedAt(time.local(time.now()).minusDays(1));
        row.setState("PENDING");
        row.setVersion(VERSION);
        row.setAttemptId(5L);
        attempt.setId(5L);
        attempt.setRequestId("request");
        attempt.setServiceId(10L);
        attempt.setOperation("CHAT_COMPLETIONS");
        attempt.setOutcome("COMPLETED");
        attempt.setPricingJson(JSON.toJSONString(requestPrice()));
        invocation.setTenantId(TENANT_ID);
        invocation.setRequestId("request");
        invocation.setEventId("event");
        invocation.setSource(row.getSource());
        invocation.setRequestStartedAt(row.getRequestStartedAt());
        invocation.setState("UNKNOWN");
        when(attempts.selectById(5L)).thenReturn(attempt);
        when(invocations.lockRequest("request")).thenReturn(invocation);
        when(cases.lockInBatch(8L, 3L)).thenReturn(row);
        when(models.lockForRequest(TENANT_ID, "request", 10L)).thenReturn(List.of());
        doAnswer(call -> { call.getArgument(0, CostReconciliationAdjustmentDO.class).setId(20L); return 1; })
                .when(adjustments).insert(any(CostReconciliationAdjustmentDO.class));
    }

    @Test
    void sameSubmissionReturnsOriginalResultWithoutChargingOrPublishingAgain() {
        var command = command(CostReconciliationResolveReqDTO.Decision.CHARGE);
        var saved = service.resolve(row, ACTOR_ID, command);
        when(adjustments.selectOne(any())).thenReturn(saved);

        assertSame(saved, service.resolve(row, ACTOR_ID, command));
        assertEquals(VERSION + 1, row.getVersion());
        assertEquals(8L, row.getBatchId());
        assertEquals("RESOLVED", row.getState());
        assertEquals("SETTLED", invocation.getState());
        assertEquals("CASE", invocation.getReconciliationDisposition());
        assertEquals(0, new BigDecimal("13").compareTo(saved.getCostFen()));
        verify(budgets).adjustContribution(any(), any(), anyList(), any(), any());
        verify(adjustments).insert(any(CostReconciliationAdjustmentDO.class));
        var event = ArgumentCaptor.forClass(CostReconciliationEvent.class);
        verify(events).publish(event.capture(), eq(TENANT_ID));
        assertEquals("cost-reconciliation-adjustment-20", event.getValue().getEventId());
        assertEquals(20L, event.getValue().getAdjustmentId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameSubmissionKeyWithDifferentContentOrActorIsRejected(boolean differentActor) {
        var command = command(CostReconciliationResolveReqDTO.Decision.CHARGE);
        var saved = service.resolve(row, ACTOR_ID, command);
        when(adjustments.selectOne(any())).thenReturn(saved);
        var replay = differentActor ? command : command(CostReconciliationResolveReqDTO.Decision.NO_CHARGE);
        assertError(CostReconciliationErrorCode.CASE_CHANGED,
                () -> service.resolve(row, differentActor ? 43L : ACTOR_ID, replay));
        assertEquals(VERSION + 1, row.getVersion());
        assertEquals("CALCULATED", saved.getCostStatus());
        verify(adjustments).insert(any(CostReconciliationAdjustmentDO.class));
        verify(events, times(1)).publish(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESOLVED", "AUTO", "REVIEW"})
    void manualResolutionRejectsNonPendingCase(String state) {
        row.setState(state);
        assertError(CostReconciliationErrorCode.CASE_CHANGED,
                () -> service.resolve(row, ACTOR_ID, command(CostReconciliationResolveReqDTO.Decision.CHARGE)));
        assertEquals(state, row.getState());
        assertNoCorrectionWritten();
    }

    @Test
    void changedVersionOrPhysicalAttemptCannotResolveOldPage() {
        var command = command(CostReconciliationResolveReqDTO.Decision.CHARGE);
        row.setVersion(VERSION + 1);
        assertError(CostReconciliationErrorCode.CASE_CHANGED, () -> service.resolve(row, ACTOR_ID, command));
        row.setVersion(VERSION);
        var lookup = JSON.parseObject(JSON.toJSONString(row), CostReconciliationCaseDO.class);
        row.setAttemptId(6L);
        assertError(CostReconciliationErrorCode.CASE_CHANGED, () -> service.resolve(lookup, ACTOR_ID, command));
        assertNoCorrectionWritten();
    }

    @Test
    void noChargeRemovesAppliedOriginalContributionAndPersistsNullAmount() {
        var original = original();
        original.setBudgetApplied(true);
        when(settlements.lockEvent("event")).thenReturn(original);

        var saved = service.resolve(row, ACTOR_ID, command(CostReconciliationResolveReqDTO.Decision.NO_CHARGE));

        assertEquals("NOT_CHARGEABLE", saved.getCostStatus());
        assertNull(saved.getCostFen());
        assertNull(saved.getUsageJson());
        var next = ArgumentCaptor.forClass(CostBudgetSettlementDO.class);
        verify(budgets).adjustContribution(any(), any(), anyList(), same(original), next.capture());
        assertEquals("NOT_CHARGEABLE", next.getValue().getCostStatus());
        assertEquals(row.getRequestStartedAt(), next.getValue().getRequestStartedAt());
        assertEquals(10L, next.getValue().getServiceId());
    }

    @Test
    void tokenChargeUsesFrozenPriceAndReplacesLatestCorrectionInsteadOfOriginal() {
        attempt.setPricingJson(JSON.toJSONString(new PricingSnapshot(1L, BillingMode.TOKEN, "CNY", null, null,
                new BigDecimal("200"), new BigDecimal("50"), new BigDecimal("800"), null, null, null)));
        var previous = new CostReconciliationAdjustmentDO();
        previous.setServiceId(11L);
        previous.setCostStatus("CALCULATED");
        previous.setCostFen(new BigDecimal("9"));
        when(adjustments.latest(3L)).thenReturn(previous);
        var command = new CostReconciliationResolveReqDTO(CostReconciliationResolveReqDTO.Decision.CHARGE,
                true, VERSION, "submission-1", 1_000_000L, 500_000L, 1_000_000L);

        var saved = service.resolve(row, ACTOR_ID, command);

        assertEquals(0, new BigDecimal("925").compareTo(saved.getCostFen()));
        assertEquals(new ReconciliationEvidence.Tokens(1_000_000L, 500_000L, 1_000_000L),
                JSON.parseObject(saved.getUsageJson(), ReconciliationEvidence.Tokens.class));
        var before = ArgumentCaptor.forClass(CostBudgetSettlementDO.class);
        var after = ArgumentCaptor.forClass(CostBudgetSettlementDO.class);
        verify(budgets).adjustContribution(any(), any(), anyList(), before.capture(), after.capture());
        assertEquals(11L, before.getValue().getServiceId());
        assertEquals(new BigDecimal("9"), before.getValue().getEstimatedCostFen());
        assertEquals(10L, after.getValue().getServiceId());
        assertEquals(saved.getCostFen(), after.getValue().getEstimatedCostFen());
    }

    static Stream<Arguments> invalidCommands() {
        return Stream.of(
                Arguments.of(BillingMode.REQUEST, false, CostReconciliationResolveReqDTO.Decision.CHARGE,
                        null, null, null),
                Arguments.of(BillingMode.REQUEST, true, CostReconciliationResolveReqDTO.Decision.CHARGE,
                        1L, 0L, 0L),
                Arguments.of(BillingMode.REQUEST, true, CostReconciliationResolveReqDTO.Decision.NO_CHARGE,
                        0L, null, null),
                Arguments.of(BillingMode.TOKEN, true, CostReconciliationResolveReqDTO.Decision.CHARGE,
                        null, 0L, 0L),
                Arguments.of(BillingMode.TOKEN, true, CostReconciliationResolveReqDTO.Decision.CHARGE,
                        1L, 2L, 0L),
                Arguments.of(BillingMode.TOKEN, true, CostReconciliationResolveReqDTO.Decision.CHARGE,
                        -1L, 0L, 0L));
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void invalidBillingEvidenceDoesNotWriteCorrection(BillingMode mode, boolean confirmed,
            CostReconciliationResolveReqDTO.Decision decision, Long input, Long cached, Long output) {
        if (mode == BillingMode.TOKEN) {
            attempt.setPricingJson(JSON.toJSONString(new PricingSnapshot(1L, mode, "CNY", null, null,
                    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, null, null)));
        }
        var command = new CostReconciliationResolveReqDTO(decision, confirmed, VERSION,
                "submission-1", input, cached, output);
        assertError(CostReconciliationErrorCode.INVALID_EVIDENCE, () -> service.resolve(row, ACTOR_ID, command));
        assertEquals("PENDING", row.getState());
        assertNoCorrectionWritten();
    }

    @Test
    void unpricedManualChargeAndMissingOrForeignAttemptAreRejected() {
        var command = command(CostReconciliationResolveReqDTO.Decision.CHARGE);
        attempt.setPricingJson(JSON.toJSONString(PricingSnapshot.unpriced()));
        assertError(CostReconciliationErrorCode.INVALID_EVIDENCE, () -> service.resolve(row, ACTOR_ID, command));
        attempt.setRequestId("another-request");
        assertError(CostReconciliationErrorCode.INVALID_EVIDENCE, () -> service.resolve(row, ACTOR_ID, command));
        when(attempts.selectById(5L)).thenReturn(null);
        assertError(CostReconciliationErrorCode.INVALID_EVIDENCE, () -> service.resolve(row, ACTOR_ID, command));
        assertNoCorrectionWritten();
    }

    @Test
    void permissionFailureAndCaseDisappearanceAreUnavailable() {
        doThrow(new ClientException("membership revoked")).when(permissions).requireAdmin(TENANT_ID, ACTOR_ID);
        assertError(CostReconciliationErrorCode.BATCH_UNAVAILABLE,
                () -> service.resolve(row, ACTOR_ID, command(CostReconciliationResolveReqDTO.Decision.CHARGE)));
        verifyNoInteractions(attempts);
        doNothing().when(permissions).requireAdmin(TENANT_ID, ACTOR_ID);
        when(cases.lockInBatch(8L, 3L)).thenReturn(null);
        assertError(CostReconciliationErrorCode.BATCH_UNAVAILABLE,
                () -> service.resolve(row, ACTOR_ID, command(CostReconciliationResolveReqDTO.Decision.CHARGE)));
        assertNoCorrectionWritten();
    }

    static Stream<Consumer<CostBudgetInvocationDO>> conflictingIdentity() {
        return Stream.of(value -> value.setTenantId(99L), value -> value.setEventId("foreign-event"),
                value -> value.setApiKeyId(99L), value -> value.setSource("API_KEY"),
                value -> value.setRequestStartedAt(value.getRequestStartedAt().plusSeconds(1)));
    }

    @ParameterizedTest
    @MethodSource("conflictingIdentity")
    void originalIdentityMismatchCannotMoveFees(Consumer<CostBudgetInvocationDO> change) {
        change.accept(invocation);
        assertThrows(CostBudgetSnapshot.ConflictException.class,
                () -> service.resolve(row, ACTOR_ID, command(CostReconciliationResolveReqDTO.Decision.CHARGE)));
        assertNoCorrectionWritten();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTO", "REVIEW"})
    void currentAutomaticVersionCanResolveButOldWorkCannotRepeatIt(String state) {
        row.setState(state);
        row.setVersion("REVIEW".equals(state) ? VERSION + 1 : VERSION);
        var saved = service.automatic(row, VERSION);
        assertNull(saved.getActorId());
        assertEquals("automatic", saved.getSubmissionKey());
        assertEquals("CALCULATED", saved.getCostStatus());
        assertEquals("RESOLVED", row.getState());
        assertNull(service.automatic(row, VERSION));
        verify(adjustments).insert(any(CostReconciliationAdjustmentDO.class));
        verifyNoInteractions(permissions, access);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_SENT", "UNPRICED", "UNKNOWN"})
    void automaticEvidenceKeepsItsOwnCostClassification(String outcome) {
        row.setState("AUTO");
        attempt.setOutcome(outcome);
        if ("UNPRICED".equals(outcome)) {
            attempt.setPricingJson(JSON.toJSONString(PricingSnapshot.unpriced()));
        }
        if ("UNKNOWN".equals(outcome)) {
            assertError(CostReconciliationErrorCode.INVALID_EVIDENCE, () -> service.automatic(row, VERSION));
            assertNoCorrectionWritten();
        } else {
            var saved = service.automatic(row, VERSION);
            assertEquals("NOT_SENT".equals(outcome) ? "NOT_CHARGEABLE" : "UNPRICED", saved.getCostStatus());
            assertNull(saved.getCostFen());
        }
    }

    @Test
    void adjustmentWriteFailurePropagatesBeforeCaseAndInvocationBecomeSettled() {
        var failure = new IllegalStateException("adjustment storage unavailable");
        doThrow(failure).when(adjustments).insert(any(CostReconciliationAdjustmentDO.class));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.resolve(row, ACTOR_ID, command(CostReconciliationResolveReqDTO.Decision.CHARGE))));
        assertEquals("PENDING", row.getState());
        assertEquals("UNKNOWN", invocation.getState());
        verify(cases, never()).updateById(any());
        verifyNoInteractions(events, signals);
    }

    private CostBudgetSettlementDO original() {
        var original = new CostBudgetSettlementDO();
        original.setTenantId(TENANT_ID);
        original.setRequestId("request");
        original.setSource("PLAYGROUND");
        return original;
    }

    private PricingSnapshot requestPrice() {
        return new PricingSnapshot(1L, BillingMode.REQUEST, "CNY", null, null, null, null, null,
                new BigDecimal("13"), null, null);
    }

    private CostReconciliationResolveReqDTO command(CostReconciliationResolveReqDTO.Decision decision) {
        return new CostReconciliationResolveReqDTO(decision, true, VERSION, "submission-1", null, null, null);
    }

    private void assertError(CostReconciliationErrorCode error, org.junit.jupiter.api.function.Executable action) {
        assertEquals(error.code(), assertThrows(ClientException.class, action).getErrorCode());
    }

    private void assertNoCorrectionWritten() {
        verify(adjustments, never()).insert(any(CostReconciliationAdjustmentDO.class));
        verify(cases, never()).updateById(any());
        verifyNoInteractions(events, signals);
    }
}
