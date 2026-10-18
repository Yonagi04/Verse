package com.yonagi.verse.service.usage.user;

import com.yonagi.verse.service.user.costalert.UserCostAlertTriggerService;
import com.yonagi.verse.support.UserUsageTestFixtures;
import com.yonagi.verse.common.config.UserModelUsageProperties;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserModelUsageProjectionServiceTest {
    final UserModelUsageProperties properties=new UserModelUsageProperties();
    final UserModelUsageAccessPolicy access=mock(UserModelUsageAccessPolicy.class);
    final TokenUsageMapper facts=mock(TokenUsageMapper.class);
    final TokenUsageCostMapper costs=mock(TokenUsageCostMapper.class);
    final UserModelUsageProjectionMapper work=mock(UserModelUsageProjectionMapper.class);
    final UserModelUsageDailyMapper daily=mock(UserModelUsageDailyMapper.class);
    final UserCostAlertTriggerService capture=mock(UserCostAlertTriggerService.class);
    final UserModelUsageProjectionService service=new UserModelUsageProjectionService(properties,access,facts,costs,work,daily,capture,UserUsageTestFixtures.TIME);
    UserModelUsageProjectionDO task; TokenUsageDO fact; TokenUsageCostDO cost;
    @BeforeEach void setup() {
        UserUsageTestFixtures.tables(); properties.setProjectionEnabled(true);
        task=new UserModelUsageProjectionDO(); task.setUserId(1L); task.setUsageId(10L); task.setCanonicalUsageId(10L);
        task.setStatus("PENDING"); task.setAttemptCount(0); task.setNextRetryAt(UserUsageTestFixtures.TIME.local(UserUsageTestFixtures.TIME.now())); when(work.selectOne(any())).thenReturn(task);
        fact=new TokenUsageDO(); fact.setId(10L); fact.setUserId(1L); fact.setTenantId(2L); fact.setSource("PLAYGROUND"); fact.setRequestId("r"); fact.setStatus("SUCCESS");
        fact.setRequestStartedAt(LocalDateTime.of(2026,10,17,23,59)); when(facts.selectById(10L)).thenReturn(fact);
        cost=new TokenUsageCostDO(); cost.setCostStatus("CALCULATED"); cost.setCurrency("CNY"); cost.setEstimatedCostFen(new BigDecimal("0.123456789012345678")); when(costs.selectOne(any())).thenReturn(cost);
    }
    @Test void successfulContributionBelongsToOriginalDayAndReprocessingDoesNothing() {
        service.project(1L,10L); var contribution=org.mockito.ArgumentCaptor.forClass(UserModelUsageDailyDO.class); verify(daily).contribute(contribution.capture());
        assertEquals(LocalDate.of(2026,10,17),contribution.getValue().getUsageDate()); assertEquals(1,contribution.getValue().getSuccessCount());
        assertEquals(cost.getEstimatedCostFen(),contribution.getValue().getKnownCostFen()); assertEquals("APPLIED",task.getStatus()); verify(capture).capture(1L);
        service.project(1L,10L); verify(daily,times(1)).contribute(any()); verify(capture,times(1)).capture(1L);
    }
    @Test void equivalentDuplicateHasCredentialButNoContributionAndConflictPausesFurtherAlerts() {
        task.setCanonicalUsageId(9L); var canonical=new TokenUsageDO(); org.springframework.beans.BeanUtils.copyProperties(fact,canonical); canonical.setId(9L); when(facts.selectById(9L)).thenReturn(canonical);
        var applied=new UserModelUsageProjectionDO(); applied.setStatus("APPLIED"); when(work.selectOne(any())).thenReturn(task,applied);
        service.project(1L,10L); assertEquals("DUPLICATE",task.getStatus()); verify(daily,never()).contribute(any());
        task.setStatus("PENDING"); canonical.setStatus("FAIL"); when(work.selectOne(any())).thenReturn(task);
        clearInvocations(capture); service.project(1L,10L); assertEquals("FAILED",task.getStatus()); assertEquals("LOGICAL_CONFLICT",task.getLastErrorCode()); verifyNoInteractions(capture);
    }
    @Test void failureBackoffIsBoundedAndExhaustionIsVisible() {
        service.failed(1L,10L,"UNAVAILABLE"); assertEquals(1,task.getAttemptCount()); assertEquals("PENDING",task.getStatus());
        assertEquals(UserUsageTestFixtures.TIME.local(UserUsageTestFixtures.TIME.now()).plusSeconds(2),task.getNextRetryAt());
        task.setAttemptCount(9); service.failed(1L,10L,"UNAVAILABLE"); assertEquals("FAILED",task.getStatus()); assertEquals("UNAVAILABLE",task.getLastErrorCode());
    }
    @Test void missingAndForeignFactsCannotCreateUserContributions() {
        when(facts.selectById(10L)).thenReturn(null); assertThrows(IllegalStateException.class,()->service.project(1L,10L));
        fact.setUserId(2L); when(facts.selectById(10L)).thenReturn(fact); assertThrows(IllegalStateException.class,()->service.project(1L,10L)); verifyNoInteractions(daily,capture);
    }
    @Test void enqueueKeepsOneCanonicalIdentityAndCreatesSeparateCredentialForHistoricalDuplicate() {
        when(work.selectOne(any())).thenReturn(null,null);
        service.enqueue(1L,10L); var inserted=org.mockito.ArgumentCaptor.forClass(UserModelUsageProjectionDO.class); verify(work).insert(inserted.capture());
        assertEquals("PENDING",inserted.getValue().getStatus()); assertNotNull(inserted.getValue().getLogicalKey()); assertEquals(10L,inserted.getValue().getCanonicalUsageId());
        clearInvocations(work); when(work.selectOne(any())).thenReturn(null,task); task.setUsageId(9L); service.enqueue(1L,10L);
        verify(work).insert(inserted.capture()); assertNull(inserted.getValue().getLogicalKey()); assertEquals(9L,inserted.getValue().getCanonicalUsageId());
    }
    @Test void disabledProducerDoesNothingAndUnattributableHistoryIsIsolatedWithoutFakeDates() {
        properties.setProjectionEnabled(false); service.enqueue(1L,10L); verifyNoInteractions(facts,work);
        properties.setProjectionEnabled(true); when(work.selectOne(any())).thenReturn(null); fact.setRequestStartedAt(null);
        service.enqueue(1L,10L); var inserted=org.mockito.ArgumentCaptor.forClass(UserModelUsageProjectionDO.class); verify(work).insert(inserted.capture());
        assertEquals("FAILED",inserted.getValue().getStatus()); assertEquals("IDENTITY_INCOMPLETE",inserted.getValue().getLastErrorCode());
        fact.setUserId(2L); assertThrows(IllegalStateException.class,()->service.enqueue(1L,10L));
    }
    @Test void incompleteLogicalIdentityCannotCreateCanonicalOrSuccessfulWork() {
        when(work.selectOne(any())).thenReturn(null);
        List<java.util.function.Consumer<TokenUsageDO>> invalid=List.of(x->x.setRequestId(null),x->x.setRequestId(" "),x->x.setTenantId(null),x->x.setSource(null),x->x.setSource("UNKNOWN"));
        for (var change:invalid) {
            fact.setRequestId("r"); fact.setTenantId(2L); fact.setSource("PLAYGROUND"); change.accept(fact); clearInvocations(work);
            service.enqueue(1L,10L); var inserted=org.mockito.ArgumentCaptor.forClass(UserModelUsageProjectionDO.class); verify(work).insert(inserted.capture());
            assertEquals("FAILED",inserted.getValue().getStatus()); assertEquals("IDENTITY_INCOMPLETE",inserted.getValue().getLastErrorCode()); assertNull(inserted.getValue().getLogicalKey());
        }
        verifyNoInteractions(daily,capture);
    }

    @Test void duplicateWaitsForCanonicalAndNumericOverflowCannotMutateDailyTotals() {
        task.setCanonicalUsageId(9L); var canonical=new TokenUsageDO(); org.springframework.beans.BeanUtils.copyProperties(fact,canonical); canonical.setId(9L);
        when(facts.selectById(9L)).thenReturn(canonical); when(work.selectOne(any())).thenReturn(task,null);
        assertThrows(IllegalStateException.class,()->service.project(1L,10L)); assertEquals("PENDING",task.getStatus()); verifyNoInteractions(daily,capture);
        task.setCanonicalUsageId(10L); when(work.selectOne(any())).thenReturn(task);
        var previous=new UserModelUsageDailyDO(); previous.setKnownCostFen(BigDecimal.ZERO); previous.setSuccessCount(Long.MAX_VALUE); previous.setUnknownCostCount(0L);
        when(daily.selectOne(any())).thenReturn(previous);
        assertThrows(ArithmeticException.class,()->service.project(1L,10L)); verify(daily,never()).contribute(any()); assertEquals("PENDING",task.getStatus());
    }

}
