package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.service.usage.user.UserModelUsageReadService;
import com.yonagi.verse.service.usage.user.UserModelUsageAccessPolicy;
import com.yonagi.verse.support.UserUsageTestFixtures;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserCostAlertTriggerServiceTest {
    final UserCostAlertConfigMapper configs=mock(UserCostAlertConfigMapper.class);
    final UserCostAlertEventMapper events=mock(UserCostAlertEventMapper.class);
    final UserCostAlertDeliveryMapper deliveries=mock(UserCostAlertDeliveryMapper.class);
    final UserModelUsageReadService usage=mock(UserModelUsageReadService.class);
    final UserCostAlertTriggerService service=new UserCostAlertTriggerService(configs,events,deliveries,usage,UserUsageTestFixtures.TIME);
    UserCostAlertConfigDO config;
    @BeforeEach void setup() {
        UserUsageTestFixtures.tables(); config=UserUsageTestFixtures.config(); when(configs.selectById(1L)).thenReturn(config);
        when(usage.periodCost(anyLong(),any(),any())).thenReturn(Optional.of(new UserModelUsageReadService.PeriodCost(new BigDecimal("1200"),false)));
    }
    @Test void dayAndWeekEachCreateOneFactAndOnlySelectedEmailForKnownLowerBound() {
        service.capture(1L); var facts=ArgumentCaptor.forClass(UserCostAlertEventDO.class); verify(events,times(2)).insert(facts.capture());
        assertEquals(List.of("DAY","WEEK"),facts.getAllValues().stream().map(UserCostAlertEventDO::getPeriodType).toList());
        assertTrue(facts.getAllValues().stream().allMatch(e -> e.getUserId()==1 && !e.getCostComplete() && e.getKnownCostFen().compareTo(new BigDecimal("1200"))==0));
        var tasks=ArgumentCaptor.forClass(UserCostAlertDeliveryDO.class); verify(deliveries,times(2)).insert(tasks.capture());
        assertTrue(tasks.getAllValues().stream().allMatch(t -> "EMAIL".equals(t.getChannel()) && t.getRecipientUserId()==1 && t.getRequestId().length()==64));
    }
    @Test void previouslyTriggeredPeriodAndConflictingFactsDoNotGenerateAnotherReminder() {
        when(events.selectOne(any())).thenReturn(UserUsageTestFixtures.event()); service.capture(1L); verify(events,never()).insert(any(UserCostAlertEventDO.class));
        when(events.selectOne(any())).thenReturn(null); when(usage.periodCost(anyLong(),any(),any())).thenReturn(Optional.empty()); service.capture(1L); verifyNoInteractions(deliveries);
    }
    @Test void disabledDimensionsAndSubThresholdCostsDoNotTrigger() {
        config.setDailyEnabled(false); config.setWeeklyEnabled(false); service.capture(1L); verifyNoInteractions(deliveries);
        config.setDailyEnabled(true); when(usage.periodCost(anyLong(),any(),any())).thenReturn(Optional.of(new UserModelUsageReadService.PeriodCost(new BigDecimal("999.999999999999999999"),false)));
        service.capture(1L); verify(events,never()).insert(any(UserCostAlertEventDO.class));
    }
}
