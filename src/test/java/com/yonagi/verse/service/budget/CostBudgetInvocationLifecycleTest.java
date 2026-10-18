package com.yonagi.verse.service.budget;

import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.async.outbox.UsageOutboxStager;
import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.service.forward.UpstreamExecutionOutcome;
import com.yonagi.verse.service.pricing.CostResult;
import com.yonagi.verse.support.BudgetTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只验证用例决策、副作用与重试；真实锁序、提交及故障恢复另用隔离库演练。 */
class CostBudgetInvocationLifecycleTest {
    private final Instant now = Instant.parse("2026-10-18T03:00:00Z");
    private CostBudgetService service;
    private BudgetExecutionRegistry registry;
    private CostBudgetInvocationMapper invocations;
    private CostBudgetSettlementMapper settlements;
    private LlmCostBudgetLedger models;
    private TenantCostBudgetLedger tenants;
    private LlmCostBudgetDO model;
    private PlatformTransactionManager manager;
    private CostBudgetInvocationDO row;
    private Clock clock;
    private UserContext actor;

    @BeforeEach void setup() {
        registry = new BudgetExecutionRegistry(mock(RedissonClient.class));
        invocations = mock(CostBudgetInvocationMapper.class); settlements = mock(CostBudgetSettlementMapper.class);
        models = mock(LlmCostBudgetLedger.class); tenants = BudgetTestSupport.disabledTenantLedger();
        manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        model = new LlmCostBudgetDO(); model.setTenantId(2L); model.setServiceId(10L);
        when(models.lock(2L,10L)).thenReturn(model);
        when(models.lockForRequest(any(),any(),any())).thenReturn(List.of(model));
        when(models.decision(model)).thenReturn(new CostBudgetDecision(List.of(),false));
        clock = mock(Clock.class); when(clock.instant()).thenReturn(now);
        var keys = mock(ApiKeyMapper.class); var key = new ApiKeyDO(); key.setApiKeyId(3L); key.setTenantId(2L);
        when(keys.lockBudgetKey(2L,3L)).thenReturn(key);
        service = new CostBudgetService(keys,invocations,settlements,mock(CostBudgetPeriodMapper.class),
                new BudgetPeriodResolver(clock),registry,mock(UsageOutboxStager.class),new UsageOutboxProperties(),
                manager,models,tenants,mock(UserMapper.class));
        actor = new UserContext().setCurrentTenantId(2L).setApiKeyId(3L);
        when(invocations.lockRequest("request")).thenAnswer(i -> row);
        when(invocations.insert(any(CostBudgetInvocationDO.class))).thenAnswer(i -> { row=i.getArgument(0); return 1; });
    }

    private void begin(BudgetInvocationMode mode) { service.begin(actor,10L,"request",now.minusSeconds(600),mode); }

    @Test void budgetRefusalHasNoFeesAndPersistsOriginalFailureWithNoUpstream() {
        begin(BudgetInvocationMode.SYNC); var attempt=service.attempt("request");
        when(models.decision(model)).thenReturn(new CostBudgetDecision(List.of(),true));
        var failure=assertThrows(CostBudgetUnavailableException.class,()->service.permit(actor,10L,"request",attempt));
        service.noteFailure("request","BUDGET_CHECK",failure.getErrorCode());
        service.abandon(actor,"request");
        assertEquals("NO_UPSTREAM",row.getState()); assertEquals("BUDGET_CHECK",row.getFailureStage());
        assertEquals(failure.getErrorCode(),row.getFailureCode()); assertNull(registry.local("request"));
        assertThrows(CostBudgetUnavailableException.class,attempt::startSending);
        verify(settlements,never()).insert(any(CostBudgetSettlementDO.class));
    }

    @ParameterizedTest @EnumSource(BudgetInvocationMode.class)
    void recoveryDoesNotAbandonPreparingOrExecutingCallsBecauseOfAge(BudgetInvocationMode mode) {
        begin(mode); service.recoverInvocation(row);
        assertEquals("RUNNING",row.getState()); assertTrue(registry.confirm(row));
        if (mode==BudgetInvocationMode.STREAM) { service.prepareStream(actor,10L,"request"); service.subscribe("request"); }
        else service.permit(actor,10L,"request");
        when(clock.instant()).thenReturn(now.plusSeconds(600)); service.recoverInvocation(row);
        assertEquals("RUNNING",row.getState()); assertTrue(service.hasUpstream("request"));
    }

    @Test void recoveryExpiresOnlyThirtySecondsAfterStreamPreparation() {
        begin(BudgetInvocationMode.STREAM); service.prepareStream(actor,10L,"request");
        when(clock.instant()).thenReturn(now.plusSeconds(29)); service.recoverInvocation(row);
        assertEquals("RUNNING",row.getState());
        when(clock.instant()).thenReturn(now.plusSeconds(30)); service.recoverInvocation(row);
        assertEquals("NO_UPSTREAM",row.getState());
        assertThrows(CostBudgetUnavailableException.class,()->service.subscribe("request"));
    }

    @Test void failedCleanupRetainsProofAndOriginalOwnerCanClearRecoveryUnknown() {
        begin(BudgetInvocationMode.SYNC); var attempt=service.attempt("request");
        var tenant=tenants.lock(2L);
        doThrow(new IllegalStateException("storage unavailable")).when(tenants).lock(2L);
        service.abandon(actor,"request");
        assertNotNull(registry.local("request")); assertFalse(registry.confirm(row));
        assertThrows(CostBudgetUnavailableException.class,attempt::startSending);
        row.setState("UNKNOWN"); row.setLastErrorCode("OWNER_UNCONFIRMED");
        doReturn(tenant).when(tenants).lock(2L); when(clock.instant()).thenReturn(now.plusSeconds(10));
        service.retryUnsentCleanup();
        assertEquals("NO_UPSTREAM",row.getState()); assertNull(registry.local("request"));
    }

    @Test void cleanupRetryDoesNotDependOnDatabaseRecoverableScanAfterLostCommitReply() {
        begin(BudgetInvocationMode.SYNC);
        doThrow(new IllegalStateException("commit reply lost")).doNothing().when(manager).commit(any());
        service.abandon(actor,"request");
        assertEquals("NO_UPSTREAM",row.getState()); assertNotNull(registry.local("request"));
        when(clock.instant()).thenReturn(now.plusSeconds(10)); service.retryUnsentCleanup();
        assertNull(registry.local("request")); verify(invocations,never()).recoverable();
    }

    @ParameterizedTest @ValueSource(strings={"owner","generation","event","tenant","key","SETTLED","FINALIZING","realUnknown","failure","fact"})
    void cleanupCannotOverrideForeignIdentityRealUnknownOrExistingFacts(String guard) {
        begin(BudgetInvocationMode.SYNC);
        switch(guard) {
            case "owner" -> row.setOwnerId("another-owner");
            case "generation" -> row.setOwnerGeneration("old-generation");
            case "event" -> row.setEventId("different-event");
            case "tenant" -> row.setTenantId(9L);
            case "key" -> row.setApiKeyId(9L);
            case "SETTLED", "FINALIZING" -> row.setState(guard);
            case "realUnknown" -> { row.setState("UNKNOWN"); row.setLastErrorCode("UPSTREAM_RESULT_UNKNOWN"); }
            case "failure" -> { row.setState("UNKNOWN"); row.setLastErrorCode("OWNER_UNCONFIRMED"); row.setFailureCode("SETTLEMENT_FAILED"); }
            case "fact" -> when(settlements.lockRequest(2L,"request")).thenReturn(new CostBudgetSettlementDO());
        }
        String original=row.getState(); service.abandon(actor,"request");
        assertEquals(original,row.getState()); assertNotNull(registry.local("request"));
        verify(invocations,never()).updateById(any());
    }

    @Test void sentUnknownAndOriginalCauseSurviveRecoveryAndCannotBeAutoCleared() {
        begin(BudgetInvocationMode.SYNC); service.permit(actor,10L,"request");
        assertEquals(UpstreamExecutionOutcome.UNKNOWN,service.outcome("request"));
        service.noteFailure("request","UPSTREAM","A000805");
        service.markUnknown(2L,3L,"request","UPSTREAM_RESULT_UNKNOWN");
        service.recoverInvocation(row); service.abandon(actor,"request");
        assertEquals("UNKNOWN",row.getState()); assertEquals("UPSTREAM_RESULT_UNKNOWN",row.getLastErrorCode());
        assertEquals("UPSTREAM",row.getFailureStage()); assertEquals("A000805",row.getFailureCode());
    }

    @Test void recoveryObservationDoesNotOccupyFirstRealFailureFields() {
        begin(BudgetInvocationMode.SYNC); registry.finalizing("request"); service.recoverInvocation(row);
        assertEquals("OWNER_UNCONFIRMED",row.getLastErrorCode()); assertNull(row.getFailureCode());
        service.noteFailure("request","UPSTREAM","A000805"); service.markUnknown(2L,3L,"request","SETTLEMENT_FAILED");
        assertEquals("A000805",row.getFailureCode()); assertEquals("UPSTREAM",row.getFailureStage());
        assertEquals("OWNER_UNCONFIRMED",row.getLastErrorCode());
    }

    private TokenUsageEvent event() {
        var event=new TokenUsageEvent(); event.setEventId(row.getEventId()); event.setKey(row.getEventId());
        event.setTenantId(2L); event.setApiKeyId(3L); event.setServiceId(10L); event.setUserId(1L);
        event.setSource("API_KEY"); event.setOperation("CHAT_COMPLETIONS"); event.setRequestId("request");
        event.setRequestStartedAt(now); event.setStatus("FAIL"); event.setExecutionOutcome(UpstreamExecutionOutcome.UNKNOWN);
        event.setCostResult(CostResult.of(CostStatus.UNCALCULABLE)); return event;
    }

    @Test void contradictoryLateSnapshotDoesNotReopenNoUpstreamAndFencesScopes() {
        begin(BudgetInvocationMode.SYNC); service.abandon(actor,"request"); service.settle(event());
        assertEquals("NO_UPSTREAM",row.getState());
        verify(settlements,never()).insert(any(CostBudgetSettlementDO.class));
        verify(tenants).unavailable(any(),eq("SNAPSHOT_CONFLICT")); verify(models).unavailable(any(),eq("SNAPSHOT_CONFLICT"));
        service.retryLocalSnapshots(); verify(tenants,times(2)).unavailable(any(),eq("SNAPSHOT_CONFLICT"));
    }

    @Test void identicalAlreadyAppliedHistoricalSnapshotRemainsIdempotentAfterManualRepair() {
        begin(BudgetInvocationMode.SYNC); service.abandon(actor,"request"); var event=event();
        var fact=new CostBudgetSettlementDO(); fact.setEventId(row.getEventId()); fact.setTenantId(2L); fact.setApiKeyId(3L);
        fact.setRequestId("request"); fact.setServiceId(10L); fact.setBudgetApplied(true); fact.setPayloadHash(CostBudgetSnapshot.fingerprint(event));
        when(settlements.lockEvent(row.getEventId())).thenReturn(fact); when(settlements.selectOne(any())).thenReturn(fact);
        service.settle(event);
        assertEquals("NO_UPSTREAM",row.getState()); verify(settlements,never()).updateById(any());
        verify(tenants,never()).unavailable(any(),anyString());
    }

    @Test void pendingFrozenSnapshotPreventsCleanupEvenWhenNoSettlementWasSaved() {
        begin(BudgetInvocationMode.SYNC); var event=event();
        assertTrue(registry.noUpstream("request"));
        // 暂存前存储失败，仍须保留冻结的原终态，而不能按空账本自动清理。
        when(settlements.lockEvent(anyString())).thenThrow(new IllegalStateException("snapshot store unavailable"));
        service.settle(event);
        row.setState("UNKNOWN"); row.setLastErrorCode("OWNER_UNCONFIRMED"); row.setFailureCode(null);
        service.abandon(actor,"request");
        assertEquals("UNKNOWN",row.getState()); assertNotNull(registry.local("request"));
        verify(settlements,never()).insert(any(CostBudgetSettlementDO.class));
    }

    @Test void foreignCleanupContextCannotConsumeTheOriginalOwnersUnsentProof() {
        begin(BudgetInvocationMode.SYNC);
        service.abandon(new UserContext().setCurrentTenantId(9L).setApiKeyId(3L),"request");
        assertEquals("RUNNING",row.getState()); assertNotNull(registry.local("request"));
        service.abandon(new UserContext().setCurrentTenantId(2L).setApiKeyId(9L),"request");
        assertEquals("RUNNING",row.getState());
        service.abandon(actor,"request"); assertEquals("NO_UPSTREAM",row.getState());
    }

    @Test void absentLocalRecordHasNoAuthorityToClearAnUnknownInvocation() {
        assertEquals(UpstreamExecutionOutcome.UNKNOWN,service.outcome("missing"));
        service.abandon(actor,"missing"); verifyNoInteractions(invocations);
    }

    @Test void unappliedContradictoryFactCannotReopenNoUpstreamOrChangeAccumulatedCost() {
        begin(BudgetInvocationMode.SYNC); service.abandon(actor,"request");
        var fact=new CostBudgetSettlementDO(); fact.setEventId(row.getEventId()); fact.setTenantId(2L); fact.setApiKeyId(3L);
        fact.setRequestId("request"); fact.setServiceId(10L); fact.setBudgetApplied(false);
        when(settlements.selectOne(any())).thenReturn(fact); when(settlements.lockEvent(row.getEventId())).thenReturn(fact);
        assertThrows(CostBudgetSnapshot.ConflictException.class,()->service.apply(row.getEventId()));
        assertEquals("NO_UPSTREAM",row.getState()); assertFalse(fact.getBudgetApplied());
        verify(tenants,never()).apply(any(),any()); verify(models,never()).apply(any(),any());
        verify(settlements,never()).updateById(any());
    }
}
