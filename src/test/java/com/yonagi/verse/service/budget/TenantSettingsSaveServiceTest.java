package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.TenantSettingsService;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantSettingsSaveServiceTest {
    final TenantMapper tenants = mock(TenantMapper.class);
    final UserTenantMapper members = mock(UserTenantMapper.class);
    final TenantCostBudgetMapper budgets = mock(TenantCostBudgetMapper.class);
    final TenantCostBudgetLedger ledger = mock(TenantCostBudgetLedger.class);
    final TenantSettingsService settings = mock(TenantSettingsService.class);
    final BudgetPeriodResolver time = new BudgetPeriodResolver(Clock.fixed(Instant.parse("2026-10-17T07:00:00Z"),ZoneOffset.UTC));
    final TenantSettingsConfirmationService proof = new TenantSettingsConfirmationService("independent-confirmation-secret-32-bytes",time);
    final TenantCostBudgetConfigurationService service = new TenantCostBudgetConfigurationService(tenants,members,budgets,ledger,settings,proof,time,mock(com.yonagi.verse.async.activity.TenantActivityRecorder.class));
    TenantDO tenant; TenantCostBudgetDO cost; TenantSettingsRespDTO base; UserTenantDO member;
    @BeforeEach void setup() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (var type : List.of(TenantDO.class,TenantCostBudgetDO.class)) com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(configuration,"tenant-save"),type);
        tenant = new TenantDO(); tenant.setTenantId(2L); tenant.setSettingsVersion(3L); tenant.setType("TEAM"); tenant.setPlaygroundEnabled(1);
        member = new UserTenantDO(); member.setRole("ADMIN");
        cost = new TenantCostBudgetDO(); cost.setId(2L); cost.setTenantId(2L); cost.setConfigVersion(4L); cost.setCostLimitEnabled(false);
        cost.setWarningEnabled(false); cost.setWarningThresholdPercent(80); cost.setWarningChannels("[]");
        base = new TenantSettingsRespDTO(); base.setTenantId(2L); base.setSettingsVersion("3"); base.setName("team"); base.setType("TEAM"); base.setRole("ADMIN"); base.setEditable(true);
        base.setJoinApprovalMode(0); base.setAuditEnabled(false); base.setActivityRecordingEnabled(false); base.setPlaygroundEnabled(true);
        when(tenants.selectActiveTenantForUpdate(2L)).thenReturn(tenant); when(members.lockActiveJoiningUser(1L)).thenReturn(1L);
        when(members.selectActiveMembershipForUpdate(1L,2L)).thenReturn(member); when(ledger.lock(2L)).thenReturn(cost);
        when(settings.getSettings(1L,2L)).thenReturn(base); when(settings.updateSettings(eq(1L),eq(2L),any())).thenReturn(base);
        when(ledger.preview(any())).thenReturn(new CostBudgetDecision(List.of(),false));
    }
    TenantSettingsSaveReqDTO request(String extra) throws Exception {
        return new ObjectMapper().readValue("{\"settingsVersion\":\"3\",\"name\":\"team\",\"description\":null,\"joinApprovalMode\":0,\"auditEnabled\":false,\"activityRecordingEnabled\":false,\"playgroundEnabled\":true"+extra+"}",TenantSettingsSaveReqDTO.class);
    }
    String target = ",\"costLimit\":{\"expectedVersion\":\"4\",\"enabled\":true,\"dailyLimitFen\":\"100\",\"weeklyLimitFen\":null,\"monthlyLimitFen\":null},\"costWarning\":{\"enabled\":false,\"thresholdPercent\":80,\"channels\":[]}";
    @Test void firstSubmitHasNoWriteAndExplicitConfirmationSavesWholeTarget() throws Exception {
        var req = request(target); req.setName("changed");
        var preview = service.save(1L,2L,req);
        assertEquals("CONFIRMATION_REQUIRED",preview.status()); assertTrue(preview.warningMessage().contains("包括 PlayGround"));
        verify(settings,never()).updateSettings(any(),any(),any()); verifyNoInteractions(budgets);
        req.setConfirmationToken(preview.confirmationToken());
        var saved = service.save(1L,2L,req);
        assertEquals("SAVED",saved.status()); assertTrue(saved.savedSnapshot().costLimit().enabled()); assertEquals("5",saved.savedSnapshot().costLimit().version());
        assertEquals("4",saved.savedSnapshot().settings().getSettingsVersion());
        verify(settings).updateSettings(1L,2L,req); verify(budgets).update(any()); verify(ledger).capture(any());
    }
    @Test void concurrentWarningChangeRefreshesProofForFrozenOriginalWarning() throws Exception {
        var req = request(target); var initial = service.save(1L,2L,req);
        cost.setWarningChannels("[\"EMAIL\"]"); cost.setConfigVersion(5L); tenant.setSettingsVersion(4L);
        req.setConfirmationToken(initial.confirmationToken());
        var refreshed = service.save(1L,2L,req);
        assertEquals("CONFIRMATION_REQUIRED",refreshed.status()); assertEquals("5",refreshed.expectedCostConfigVersion());
        assertEquals(List.of("EMAIL"),refreshed.currentSnapshot().costWarning().channels());
        req.setConfirmationToken(refreshed.confirmationToken()); req.setSettingsVersion("4");
        var result = service.save(1L,2L,req); assertEquals("SAVED",result.status()); assertEquals(List.of(),result.savedSnapshot().costWarning().channels());
    }
    @Test void changedPayloadAndIncompleteConfirmedTargetRejectWithoutWrite() throws Exception {
        var req = request(target); var preview = service.save(1L,2L,req); req.setConfirmationToken(preview.confirmationToken()); req.setName("tampered");
        assertThrows(ClientException.class, () -> service.save(1L,2L,req));
        var incomplete = request(",\"confirmationToken\":\"bad\"");
        assertThrows(ClientException.class, () -> service.save(1L,2L,incomplete)); verifyNoInteractions(budgets);
    }
    @Test void versionConflictAndUnavailablePreviewNeverSave() throws Exception {
        var req = request(target); req.setSettingsVersion("2"); assertThrows(ClientException.class, () -> service.save(1L,2L,req));
        req.setSettingsVersion("3"); when(ledger.preview(any())).thenReturn(new CostBudgetDecision(List.of(),true));
        assertThrows(ClientException.class, () -> service.save(1L,2L,req)); verifyNoInteractions(budgets);
    }
    @Test void noOpReplayDoesNotBumpVersionsOrCreateEvents() throws Exception {
        var req = request(""); req.setSettingsVersion("1");
        var saved = service.save(1L,2L,req); assertEquals("SAVED",saved.status()); assertEquals("4",saved.savedSnapshot().costLimit().version());
        verifyNoInteractions(budgets); verify(settings,never()).updateSettings(any(),any(),any());
    }
    @Test void memberOrMissingMembershipCannotReadFullOrSave() throws Exception {
        var req = request(target); member.setRole("MEMBER"); assertThrows(ClientException.class, () -> service.save(1L,2L,req));
        verifyNoInteractions(ledger,budgets);
        when(members.selectActiveMembershipForUpdate(1L,2L)).thenReturn(null);
        assertThrows(ClientException.class, () -> service.status(1L,2L));
    }
    @Test void warningOnlyAndDisablingDoNotRequireRiskConfirmation() throws Exception {
        cost.setCostLimitEnabled(true); cost.setDailyLimitFen(new BigDecimal("100"));
        var req = request(",\"costLimit\":{\"expectedVersion\":\"4\"},\"costWarning\":{\"enabled\":true,\"thresholdPercent\":99,\"channels\":[\"EMAIL\"]}");
        assertEquals("SAVED",service.save(1L,2L,req).status()); verify(ledger,never()).preview(any());
    }
    @Test void illegalConfigAndWarningReject() throws Exception {
        var noLimit = request(",\"costLimit\":{\"expectedVersion\":\"4\",\"enabled\":true}");
        assertThrows(ClientException.class, () -> service.save(1L,2L,noLimit));
        var badWarning = request(",\"costWarning\":{\"enabled\":true}");
        assertThrows(ClientException.class, () -> service.save(1L,2L,badWarning));
    }
    @Test void memberStatusPhysicallyDropsAdministratorConfiguration() {
        member.setRole("MEMBER");
        when(ledger.snapshot(cost,"3")).thenReturn(new TenantCostStatusRespDTO.Full("2","FULL","3",TenantCostBudgetLedger.config(cost),
                TenantCostBudgetLedger.warning(cost),"CNY","Asia/Shanghai",List.of("API_KEY","PLAYGROUND"),"now","READY","LIMITED","end",List.of(),List.of()));
        var result = service.status(1L,2L); var json = JSON.parseObject(JSON.toJSONString(result));
        assertEquals("SUMMARY",json.getString("visibility")); assertFalse(json.containsKey("costLimit")); assertFalse(json.containsKey("costWarning")); assertFalse(json.containsKey("periods"));
    }
    @Test void impactChangeRequiresAnotherExplicitConfirmation() throws Exception {
        var req = request(target); var first = service.save(1L,2L,req);
        var hit = new ApiKeyCostStatusRespDTO.Hit("TENANT","DAY","2026-10-18T00:00:00+08:00");
        when(ledger.preview(any())).thenReturn(new CostBudgetDecision(List.of(hit),false)); req.setConfirmationToken(first.confirmationToken());
        var refreshed = service.save(1L,2L,req); assertEquals(List.of(hit),refreshed.immediateLimits()); assertTrue(refreshed.warningMessage().contains("立即限制"));
        verify(settings,never()).updateSettings(any(),any(),any());
    }
}
