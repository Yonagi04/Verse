package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.service.usage.user.UserModelUsageReadService;
import com.yonagi.verse.service.usage.user.UserModelUsageAccessPolicy;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.support.UserUsageTestFixtures;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserCostAlertQueryServiceTest {
    final UserModelUsageAccessPolicy access=mock(UserModelUsageAccessPolicy.class);
    final UserModelUsageReadService usage=mock(UserModelUsageReadService.class);
    final UserCostAlertConfigMapper configs=mock(UserCostAlertConfigMapper.class);
    final UserCostAlertTriggerService capture=mock(UserCostAlertTriggerService.class);
    final UserCostAlertChannelReadinessService readiness=mock(UserCostAlertChannelReadinessService.class);
    final UserCostAlertQueryService query=new UserCostAlertQueryService(access,usage,configs,capture,readiness,UserUsageTestFixtures.TIME);
    @BeforeEach void setup() {
        UserUsageTestFixtures.tables();
        when(usage.snapshot(anyLong(),any(),any())).thenReturn(new UserModelUsageReadService.Snapshot(Map.of(),"READY",null,0L,false,false,Set.of()));
    }
    @Test void queryingDefaultConfigurationIsReadOnlyAndDoesNotGenerateAlerts() {
        var result=query.getSettings(1L); assertEquals("0",result.config().version()); assertFalse(result.config().daily().enabled());
        assertEquals(List.of("IN_APP"),result.config().channels()); assertEquals(2,result.periods().size());
        assertTrue(result.periods().stream().allMatch(period -> period.alertState().equals("DISABLED") && period.costComplete()));
        verify(configs,never()).insert(any(UserCostAlertConfigDO.class)); verify(capture,never()).capture(anyLong());
    }

    @Test void knownCostAndUnknownCallsRemainExactWhileTriggeredAndUndecidedPeriodsStayDistinct() {
        when(configs.selectById(1L)).thenReturn(UserUsageTestFixtures.config());
        var date=java.time.LocalDate.of(2026,10,18);
        when(usage.snapshot(anyLong(),any(),any())).thenReturn(new UserModelUsageReadService.Snapshot(Map.of(date,new UserModelUsageReadService.DailyUsage(3,new java.math.BigDecimal("1200.000000000000000001"),2)),"READY",null,0L,false,false,Set.of()));
        var event=UserUsageTestFixtures.event(); event.setTriggeredAt(UserUsageTestFixtures.TIME.local(UserUsageTestFixtures.TIME.now()));
        when(capture.existing(anyLong(),any())).thenAnswer(invocation -> "DAY".equals(((com.yonagi.verse.service.budget.BudgetPeriodResolver.Period)invocation.getArgument(1)).type()) ? event : null);
        var result=query.getSettings(1L);
        assertEquals("1200.000000000000000001",result.periods().getFirst().knownCostFen()); assertEquals("2",result.periods().getFirst().unknownCostCount());
        assertEquals("TRIGGERED",result.periods().getFirst().alertState()); assertNotNull(result.periods().getFirst().triggeredAt());
        assertEquals("UNKNOWN",result.periods().getLast().alertState()); assertFalse(result.periods().getLast().costComplete());
    }

    @Test void disabledCollectionHidesKnownCostRatherThanAdvertisingZero() {
        when(configs.selectById(1L)).thenReturn(UserUsageTestFixtures.config());
        when(usage.snapshot(anyLong(),any(),any())).thenReturn(new UserModelUsageReadService.Snapshot(Map.of(),"UNAVAILABLE",null,0L,false,false,Set.of()));
        var result=query.getSettings(1L); assertEquals("UNAVAILABLE",result.availability());
        assertTrue(result.periods().stream().allMatch(period -> period.knownCostFen()==null && period.alertState().equals("UNKNOWN")));
    }
}
