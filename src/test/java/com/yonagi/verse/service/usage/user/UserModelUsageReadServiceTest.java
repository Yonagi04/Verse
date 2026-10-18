package com.yonagi.verse.service.usage.user;

import com.yonagi.verse.dao.entity.UserModelUsageDailyDO;
import com.yonagi.verse.dao.mapper.UserModelUsageDailyMapper;
import com.yonagi.verse.dao.mapper.UserModelUsageProjectionMapper;
import com.yonagi.verse.support.UserUsageTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserModelUsageReadServiceTest {
    final UserModelUsageDailyMapper daily=mock(UserModelUsageDailyMapper.class);
    final UserModelUsageProjectionMapper work=mock(UserModelUsageProjectionMapper.class);
    final UserModelUsageAccessPolicy access=mock(UserModelUsageAccessPolicy.class);
    final UserModelUsageReadService service=new UserModelUsageReadService(daily,work,access,UserUsageTestFixtures.TIME);
    final LocalDate from=LocalDate.of(2026,10,16), to=from.plusDays(2);

    @BeforeEach void setup() {
        UserUsageTestFixtures.tables(); when(access.collecting()).thenReturn(true);
    }

    @Test void unavailableOrConflictingFactsDoNotExposeUsablePeriodCost() {
        when(access.collecting()).thenReturn(false);
        assertTrue(service.periodCost(1L,from,to).isEmpty()); verifyNoInteractions(daily,work);
        when(access.collecting()).thenReturn(true); when(work.hasConflict(1L)).thenReturn(true);
        assertTrue(service.periodCost(1L,from,to).isEmpty());
    }

    @Test void knownLowerBoundRetainsPrecisionAndUnknownCostsRemainIncomplete() {
        var a=day("999.999999999999999999",0); var b=day("0.000000000000000001",1); b.setUsageDate(from.plusDays(1));
        when(daily.selectList(any())).thenReturn(List.of(a,b));
        var lower=service.periodCost(1L,from,to).orElseThrow();
        assertEquals(0,new BigDecimal("1000").compareTo(lower.knownCostFen())); assertFalse(lower.complete());
        assertTrue(service.snapshot(1L,from,to.minusDays(1)).complete(from.plusDays(1)),"Unknown cost must not downgrade activity counts");
        when(daily.selectList(any())).thenReturn(List.of(a));
        assertTrue(service.periodCost(1L,from,to).orElseThrow().complete());
    }

    @Test void emptyPeriodIsZeroWithoutManualInitializationAndPendingFactsRemainIncomplete() {
        var zero=service.periodCost(1L,from,to).orElseThrow();
        assertEquals(BigDecimal.ZERO,zero.knownCostFen()); assertTrue(zero.complete());
        when(work.incompleteDates(eq(1L),any(),any())).thenReturn(List.of(from));
        var snapshot=service.snapshot(1L,from,to.minusDays(1));
        assertEquals("READY",snapshot.availability()); assertFalse(snapshot.complete(from)); assertTrue(snapshot.complete(from.plusDays(1)));
        assertFalse(service.periodCost(1L,from,to).orElseThrow().complete());
        verify(work,atLeastOnce()).incompleteDates(1L,from.atStartOfDay(),to.atStartOfDay());
    }

    @Test void undatedFactsKeepCostUncertainAndCollectionDisabledNeverClaimsReady() {
        when(work.hasUndated(1L)).thenReturn(true);
        assertFalse(service.periodCost(1L,from,to).orElseThrow().complete());
        when(access.collecting()).thenReturn(false);
        var snapshot=service.snapshot(1L,from,to); assertEquals("UNAVAILABLE",snapshot.availability()); assertFalse(snapshot.complete(from));
    }

    @Test void snapshotUsesLatestDailyUpdateAndClampsNegativeLag() {
        var a=day("0.123456789012345678",0); var b=day("1",0); b.setUsageDate(from.plusDays(1));
        var now=UserUsageTestFixtures.TIME.local(UserUsageTestFixtures.TIME.now()); a.setUpdatedAt(now.minusMinutes(2)); b.setUpdatedAt(now.minusMinutes(1));
        when(daily.selectList(any())).thenReturn(List.of(a,b)); when(work.oldest(1L)).thenReturn(now.minusSeconds(301));
        var snapshot=service.snapshot(1L,from,to); assertEquals(now.minusMinutes(1),snapshot.updatedAt()); assertEquals(301L,snapshot.lagSeconds());
        assertEquals(new UserModelUsageReadService.DailyUsage(3,new BigDecimal("0.123456789012345678"),0),snapshot.days().get(from));
        when(daily.selectList(any())).thenReturn(List.of(b,a)); when(work.oldest(1L)).thenReturn(now.plusSeconds(30));
        assertEquals(now.minusMinutes(1),service.snapshot(1L,from,to).updatedAt()); assertEquals(0L,service.snapshot(1L,from,to).lagSeconds());
    }

    @Test void dependencyFailureIsPropagatedInsteadOfReturningZero() {
        when(daily.selectList(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class,()->service.snapshot(1L,from,to));
        assertThrows(IllegalStateException.class,()->service.periodCost(1L,from,to));
    }

    private UserModelUsageDailyDO day(String amount,long unknown) {
        var row=new UserModelUsageDailyDO(); row.setUsageDate(from); row.setKnownCostFen(new BigDecimal(amount)); row.setUnknownCostCount(unknown); row.setSuccessCount(3L); return row;
    }
}
