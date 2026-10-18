package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.service.usage.user.UserModelUsageAccessPolicy;
import com.yonagi.verse.support.UserUsageTestFixtures;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.UserCostAlertSettingsReplaceReqDTO;
import com.yonagi.verse.dto.req.UserCostAlertSettingsReplaceReqDTO.PeriodConfig;
import com.yonagi.verse.dto.resp.UserCostAlertSettingsRespDTO.ChannelReadiness;
import com.yonagi.verse.common.convention.exception.ClientException;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserCostAlertSettingsServiceTest {
    final UserModelUsageAccessPolicy access=mock(UserModelUsageAccessPolicy.class);
    final UserCostAlertConfigMapper configs=mock(UserCostAlertConfigMapper.class);
    final UserCostAlertDeliveryMapper deliveries=mock(UserCostAlertDeliveryMapper.class);
    final UserCostAlertEventMapper events=mock(UserCostAlertEventMapper.class);
    final UserCostAlertChannelReadinessService readiness=mock(UserCostAlertChannelReadinessService.class);
    final UserCostAlertTriggerService capture=mock(UserCostAlertTriggerService.class);
    final UserCostAlertQueryService query=mock(UserCostAlertQueryService.class);
    final UserCostAlertCancellationService cancellation=mock(UserCostAlertCancellationService.class);
    final UserCostAlertSettingsService service=new UserCostAlertSettingsService(access,configs,readiness,capture,query,cancellation,UserUsageTestFixtures.TIME);
    @BeforeEach void setup() { UserUsageTestFixtures.tables(); when(access.lock(1L)).thenReturn(UserUsageTestFixtures.user()); when(readiness.readiness(any())).thenReturn(List.of(new ChannelReadiness("EMAIL",true,null,"a***@b.com"))); }
    UserCostAlertSettingsReplaceReqDTO request(String version,String amount,List<String> channels) { return new UserCostAlertSettingsReplaceReqDTO(version,new PeriodConfig(true,amount),new PeriodConfig(false,null),channels); }
    @Test void firstSavePersistsConfigurationThenChecksAlreadyAccumulatedCurrentPeriods() {
        service.replace(1L,request("0","1000",List.of("EMAIL")));
        var saved=ArgumentCaptor.forClass(UserCostAlertConfigDO.class); verify(configs).insert(saved.capture());
        assertEquals(1,saved.getValue().getConfigVersion()); assertEquals("1000",saved.getValue().getDailyThresholdFen().toPlainString());
        assertFalse(saved.getValue().getWeeklyEnabled()); assertEquals("[\"EMAIL\"]",saved.getValue().getChannelsJson());
        verify(cancellation).configurationChanged(eq(1L),any()); verify(capture).capture(1L); verify(query).getSettings(1L);
    }
    @Test void staleDifferentConfigurationCannotOverwriteAndIdenticalReplayDoesNotCreateNewVersion() {
        var current=UserUsageTestFixtures.config(); current.setWeeklyEnabled(false); current.setWeeklyThresholdFen(null); when(configs.selectById(1L)).thenReturn(current);
        assertThrows(ClientException.class,()->service.replace(1L,request("0","999",List.of("EMAIL"))));
        service.replace(1L,request("0","1000",List.of("EMAIL")));
        verify(configs,never()).insert(any(UserCostAlertConfigDO.class)); verifyNoInteractions(capture,cancellation); verify(query).getSettings(1L);
    }
    @Test void unavailableSelectedChannelDoesNotFallBackToInApp() {
        when(readiness.readiness(any())).thenReturn(List.of(new ChannelReadiness("IN_APP",true,null,null),new ChannelReadiness("EMAIL",false,"CONTACT_MISSING",null)));
        assertThrows(ClientException.class,()->service.replace(1L,request("0","1000",List.of("EMAIL")))); verify(configs,never()).insert(any(UserCostAlertConfigDO.class)); verifyNoInteractions(capture);
    }
    @Test void rejectsInvalidMoneyChannelsVersionsAndEnabledMissingAmount() {
        for (String amount:List.of("0","-1","0.1","100000000000000000000","1e3")) assertThrows(ClientException.class,()->UserCostAlertSettingsService.validate(request("0",amount,List.of("EMAIL"))));
        assertThrows(ClientException.class,()->UserCostAlertSettingsService.validate(request("-1","1",List.of("EMAIL"))));
        assertThrows(ClientException.class,()->UserCostAlertSettingsService.validate(request("0",null,List.of("EMAIL"))));
        assertThrows(ClientException.class,()->UserCostAlertSettingsService.validate(request("0","1",List.of())));
        assertThrows(ClientException.class,()->UserCostAlertSettingsService.validate(request("0","1",List.of("EMAIL","EMAIL"))));
        assertThrows(ClientException.class,()->UserCostAlertSettingsService.validate(request("0","1",List.of("ADMIN"))));
        assertDoesNotThrow(()->UserCostAlertSettingsService.validate(request("0","99999999999999999999",List.of("EMAIL"))));
    }
    @Test void completeReplacementCanClearDisabledAmountsAndRejectsMalformedTrustedPortRequests() {
        var current=UserUsageTestFixtures.config(); when(configs.selectById(1L)).thenReturn(current);
        var off=new PeriodConfig(false,null); service.replace(1L,new UserCostAlertSettingsReplaceReqDTO("1",off,off,List.of()));
        verify(configs).update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)); verify(cancellation).configurationChanged(eq(1L),any());
        assertThrows(ClientException.class,()->UserCostAlertSettingsService.validate(null));
        assertThrows(ClientException.class,()->UserCostAlertSettingsService.validate(new UserCostAlertSettingsReplaceReqDTO("0",null,off,List.of())));
        assertThrows(ClientException.class,()->UserCostAlertSettingsService.validate(new UserCostAlertSettingsReplaceReqDTO("0",off,null,List.of())));
        assertThrows(ClientException.class,()->UserCostAlertSettingsService.validate(new UserCostAlertSettingsReplaceReqDTO(null,off,off,List.of())));
        assertThrows(ClientException.class,()->UserCostAlertSettingsService.validate(new UserCostAlertSettingsReplaceReqDTO("0",off,off,null)));
        assertThrows(ClientException.class,()->UserCostAlertSettingsService.validate(new UserCostAlertSettingsReplaceReqDTO("0",off,off,List.of("EMAIL","SMS","IN_APP","EMAIL"))));
        assertThrows(ClientException.class,()->UserCostAlertSettingsService.validate(new UserCostAlertSettingsReplaceReqDTO("0",off,off,Arrays.asList((String)null))));
        assertThrows(ClientException.class,()->UserCostAlertSettingsService.validate(new UserCostAlertSettingsReplaceReqDTO("0",new PeriodConfig(null,null),off,List.of())));
    }
}
