package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.dto.req.TenantSettingsUpdateReqDTO;
import com.yonagi.verse.dto.resp.TenantSettingsRespDTO;
import com.yonagi.verse.resilience.api.RateLimiter;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.event.TenantActivityDraft;
import com.yonagi.verse.common.enums.TenantActivityType;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantSettingsServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "tenant-settings"), TenantDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "tenant-settings-membership"), UserTenantDO.class);
    }

    @Test
    void memberCanReadButCannotUpdateSettings() {
        Fixture fixture = fixture("MEMBER", teamTenant());

        TenantSettingsRespDTO response = fixture.service.getSettings(10L, 20L);
        assertEquals("MEMBER", response.getRole());
        assertFalse(response.getEditable());
        assertFalse(response.getActivityRecordingEnabled());

        assertThrows(ClientException.class,
                () -> fixture.service.updateSettings(10L, 20L, validRequest()));
        verify(fixture.tenantMapper, never()).update(any());
    }

    @Test
    void adminUpdateNormalizesLimitsAndInvalidatesCaches() {
        TenantDO before = teamTenant();
        TenantDO saved = teamTenant();
        saved.setName("Verse 团队");
        saved.setDescription(null);
        saved.setJoinApprovalMode(1);
        saved.setAuditEnabled(1);
        saved.setRateLimitRpm(null);
        saved.setRateLimitTpm(60000);
        Fixture fixture = fixture("ADMIN", before);
        when(fixture.tenantMapper.update(any())).thenReturn(1);
        when(fixture.tenantMapper.selectOne(any())).thenReturn(before, saved);

        TenantSettingsUpdateReqDTO request = validRequest();
        request.setName("  Verse 团队  ");
        request.setDescription("   ");
        request.setRateLimitRpm(0);
        request.setRateLimitTpm(60000);

        TenantSettingsRespDTO response = fixture.service.updateSettings(10L, 20L, request);

        assertEquals("Verse 团队", response.getName());
        assertNull(response.getDescription());
        assertNull(response.getRateLimitRpm());
        assertEquals(60000, response.getRateLimitTpm());
        assertTrue(response.getEditable());
        verify(fixture.redisTemplate).delete("verse:tenant:info:20");
        verify(fixture.rateLimiter).invalidateTenantRpm(20L);
    }

    @Test
    void personalTenantRejectsApprovalMode() {
        Fixture fixture = fixture("SUPER_ADMIN", personalTenant());
        TenantSettingsUpdateReqDTO request = validRequest();
        request.setJoinApprovalMode(1);

        assertThrows(ClientException.class,
                () -> fixture.service.updateSettings(10L, 20L, request));
        verify(fixture.tenantMapper, never()).update(any());
    }

    @Test
    void teamTenantRejectsUnknownApprovalMode() {
        Fixture fixture = fixture("ADMIN", teamTenant());
        TenantSettingsUpdateReqDTO request = validRequest();
        request.setJoinApprovalMode(2);

        assertThrows(ClientException.class,
                () -> fixture.service.updateSettings(10L, 20L, request));
        verify(fixture.tenantMapper, never()).update(any());
    }

    @Test
    void enablingAndDisablingProduceFirstAndLastEvents() {
        TenantDO before = teamTenant();
        TenantDO enabled = teamTenant(); enabled.setActivityRecordingEnabled(1);
        Fixture fixture = fixture("ADMIN", before); TenantActivityRecorder recorder = fixture.activityRecorder;
        when(fixture.tenantMapper.update(any())).thenReturn(1);
        when(fixture.tenantMapper.selectOne(any())).thenReturn(before, enabled);
        TenantSettingsUpdateReqDTO request = validRequest(); request.setActivityRecordingEnabled(true);
        fixture.service.updateSettings(10L, 20L, request);
        ArgumentCaptor<TenantActivityDraft> captor=ArgumentCaptor.forClass(TenantActivityDraft.class);
        verify(recorder).recordToggle(eq(20L),captor.capture());
        assertEquals(TenantActivityType.ACTIVITY_RECORDING_ENABLED,captor.getValue().type());

        reset(recorder); before.setActivityRecordingEnabled(1); TenantDO disabled=teamTenant(); disabled.setActivityRecordingEnabled(0);
        when(fixture.tenantMapper.selectOne(any())).thenReturn(before,disabled); request.setActivityRecordingEnabled(false);
        fixture.service.updateSettings(10L,20L,request); verify(recorder).recordToggle(eq(20L),captor.capture());
        assertEquals(TenantActivityType.ACTIVITY_RECORDING_DISABLED,captor.getValue().type());
    }

    @Test
    void unchangedSettingsWhileRecordingStaysClosedProduceNoEvent() {
        TenantDO before = teamTenant();
        Fixture fixture = fixture("ADMIN", before);
        when(fixture.tenantMapper.update(any())).thenReturn(1);
        when(fixture.tenantMapper.selectOne(any())).thenReturn(before, before);
        TenantSettingsUpdateReqDTO request = new TenantSettingsUpdateReqDTO();
        request.setName(before.getName());
        request.setDescription(before.getDescription());
        request.setJoinApprovalMode(before.getJoinApprovalMode());
        request.setAuditEnabled(false);
        request.setActivityRecordingEnabled(false);
        request.setRateLimitRpm(before.getRateLimitRpm());
        request.setRateLimitTpm(before.getRateLimitTpm());

        fixture.service.updateSettings(10L, 20L, request);

        verifyNoInteractions(fixture.activityRecorder);
    }

    private static Fixture fixture(String role, TenantDO tenant) {
        TenantMapper tenantMapper = mock(TenantMapper.class);
        UserTenantMapper userTenantMapper = mock(UserTenantMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RateLimiter rateLimiter = mock(RateLimiter.class);
        TenantActivityRecorder activityRecorder = mock(TenantActivityRecorder.class);
        UserTenantDO membership = new UserTenantDO();
        membership.setUserId(10L);
        membership.setTenantId(20L);
        membership.setRole(role);
        when(userTenantMapper.selectOne(any())).thenReturn(membership);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        return new Fixture(tenantMapper, redisTemplate, rateLimiter, activityRecorder,
                new TenantSettingsServiceImpl(tenantMapper, userTenantMapper, redisTemplate, rateLimiter, activityRecorder));
    }

    private static TenantDO teamTenant() {
        TenantDO tenant = new TenantDO();
        tenant.setTenantId(20L);
        tenant.setType("TEAM");
        tenant.setName("旧名称");
        tenant.setJoinApprovalMode(0);
        tenant.setAuditEnabled(0);
        tenant.setActivityRecordingEnabled(0);
        return tenant;
    }

    private static TenantDO personalTenant() {
        TenantDO tenant = teamTenant();
        tenant.setType("PERSONAL");
        return tenant;
    }

    private static TenantSettingsUpdateReqDTO validRequest() {
        TenantSettingsUpdateReqDTO request = new TenantSettingsUpdateReqDTO();
        request.setName("Verse");
        request.setDescription("统一模型网关");
        request.setJoinApprovalMode(1);
        request.setAuditEnabled(true);
        request.setActivityRecordingEnabled(false);
        request.setRateLimitRpm(1200);
        request.setRateLimitTpm(60000);
        return request;
    }

    private record Fixture(TenantMapper tenantMapper,
                           StringRedisTemplate redisTemplate,
                           RateLimiter rateLimiter,
                           TenantActivityRecorder activityRecorder,
                           TenantSettingsServiceImpl service) {
    }
}
