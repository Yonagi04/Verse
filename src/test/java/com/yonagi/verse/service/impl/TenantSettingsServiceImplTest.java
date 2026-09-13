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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private static Fixture fixture(String role, TenantDO tenant) {
        TenantMapper tenantMapper = mock(TenantMapper.class);
        UserTenantMapper userTenantMapper = mock(UserTenantMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RateLimiter rateLimiter = mock(RateLimiter.class);
        UserTenantDO membership = new UserTenantDO();
        membership.setUserId(10L);
        membership.setTenantId(20L);
        membership.setRole(role);
        when(userTenantMapper.selectOne(any())).thenReturn(membership);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        return new Fixture(tenantMapper, redisTemplate, rateLimiter,
                new TenantSettingsServiceImpl(tenantMapper, userTenantMapper, redisTemplate, rateLimiter));
    }

    private static TenantDO teamTenant() {
        TenantDO tenant = new TenantDO();
        tenant.setTenantId(20L);
        tenant.setType("TEAM");
        tenant.setName("旧名称");
        tenant.setJoinApprovalMode(0);
        tenant.setAuditEnabled(0);
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
        request.setRateLimitRpm(1200);
        request.setRateLimitTpm(60000);
        return request;
    }

    private record Fixture(TenantMapper tenantMapper,
                           StringRedisTemplate redisTemplate,
                           RateLimiter rateLimiter,
                           TenantSettingsServiceImpl service) {
    }
}
