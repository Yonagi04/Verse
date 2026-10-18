package com.yonagi.verse.service.user.activity;

import com.yonagi.verse.service.usage.user.UserModelUsageReadService;
import com.yonagi.verse.service.usage.user.UserModelUsageAccessPolicy;
import com.yonagi.verse.support.UserUsageTestFixtures;
import com.yonagi.verse.common.convention.exception.ClientException;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserModelActivityCalendarQueryServiceTest {
    final UserModelUsageAccessPolicy access=mock(UserModelUsageAccessPolicy.class);
    final UserModelUsageReadService usage=mock(UserModelUsageReadService.class);
    final UserModelActivityCalendarQueryService calendar=new UserModelActivityCalendarQueryService(access,usage,UserUsageTestFixtures.TIME);
    @BeforeEach void setup() {
        UserUsageTestFixtures.tables(); when(usage.snapshot(anyLong(),any(),any())).thenReturn(snapshot(Map.of(),Set.of()));
    }
    UserModelUsageReadService.Snapshot snapshot(Map<LocalDate,UserModelUsageReadService.DailyUsage> days,Set<LocalDate> pending) {
        return new UserModelUsageReadService.Snapshot(days,"READY",null,0L,false,false,pending);
    }
    @Test void rollingYearShowsRecordedZeroWithoutManualInitialization() {
        var result=calendar.getCalendar(1L,"ROLLING_365",null);
        assertEquals("2025-10-19",result.rangeStart()); assertEquals("2026-10-18",result.rangeEnd()); assertEquals(365,result.days().size());
        assertTrue(result.historyComplete()); assertEquals("READY",result.availability()); assertEquals("0",result.totalSuccessCount());
        assertTrue(result.days().stream().allMatch(day -> "COMPLETE".equals(day.state()) && "0".equals(day.successCount())));
        verify(access).read(1L);
    }
    @Test void leapNaturalYearAndFutureDaysUseServerBeijingToday() {
        assertEquals(366,calendar.getCalendar(1L,"YEAR",2024).days().size());
        var result=calendar.getCalendar(1L,"YEAR",2026); assertEquals("FUTURE",result.days().getLast().state()); assertNull(result.days().getLast().successCount());
        assertEquals("2026-10-18",result.today());
    }
    @Test void activityCountsStayExactAndIndependentOfUnknownCosts() {
        var date=LocalDate.of(2025,3,1);
        var day=new UserModelUsageReadService.DailyUsage(9007199254740993L,BigDecimal.ZERO,2);
        when(usage.snapshot(anyLong(),any(),any())).thenReturn(snapshot(Map.of(date,day),Set.of()));
        var result=calendar.getCalendar(1L,"YEAR",2025);
        assertEquals("9007199254740993",result.totalSuccessCount()); assertTrue(result.historyComplete());
        assertEquals("COMPLETE",result.days().get(59).state());
    }
    @Test void pendingDatesPreserveKnownCountsAndDoNotInvalidateOtherDates() {
        var date=LocalDate.of(2025,3,1); var day=new UserModelUsageReadService.DailyUsage(7,BigDecimal.ONE,0);
        when(usage.snapshot(anyLong(),any(),any())).thenReturn(snapshot(Map.of(date,day),Set.of(date)));
        var result=calendar.getCalendar(1L,"YEAR",2025);
        assertFalse(result.historyComplete()); assertEquals("7",result.totalSuccessCount());
        assertEquals("PARTIAL",result.days().get(59).state()); assertEquals("COMPLETE",result.days().getFirst().state());
        assertTrue(result.coverage().stream().anyMatch(range -> "PROCESSING_PENDING".equals(range.reasonCode())));
    }
    @Test void unavailableCollectionAndConflictsNeverAdvertiseComplete() {
        when(usage.snapshot(anyLong(),any(),any())).thenReturn(new UserModelUsageReadService.Snapshot(Map.of(),"UNAVAILABLE",null,0L,false,false,Set.of()));
        var unavailable=calendar.getCalendar(1L,"YEAR",2025); assertFalse(unavailable.historyComplete());
        assertTrue(unavailable.days().stream().allMatch(day -> "MISSING".equals(day.state()) && day.successCount()==null));
        when(usage.snapshot(anyLong(),any(),any())).thenReturn(new UserModelUsageReadService.Snapshot(Map.of(),"READY",null,0L,true,false,Set.of()));
        var conflict=calendar.getCalendar(1L,"YEAR",2025); assertFalse(conflict.historyComplete());
        assertTrue(conflict.coverage().stream().allMatch(range -> "FACTS_REQUIRE_REVIEW".equals(range.reasonCode())));
    }
    @Test void mutuallyExclusiveAndOutOfWindowParametersAreRejected() {
        assertThrows(ClientException.class,()->calendar.getCalendar(1L,"ROLLING_365",2026));
        assertThrows(ClientException.class,()->calendar.getCalendar(1L,"YEAR",null));
        assertThrows(ClientException.class,()->calendar.getCalendar(1L,"YEAR",2023));
        assertThrows(ClientException.class,()->calendar.getCalendar(1L,"YEAR",2027));
        assertThrows(ClientException.class,()->calendar.getCalendar(1L,"FROM_TO",null));
    }
}
