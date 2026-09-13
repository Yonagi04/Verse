package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.NotificationMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.resp.TenantInfoRespDTO;
import com.yonagi.verse.service.NotificationService;
import com.yonagi.verse.service.CurrentTenantStateService;
import com.yonagi.verse.service.TenantMediaService;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.helper.TenantValidationHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantCrudServiceHomeTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "tenant-home"), TenantDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "tenant-home-members"), UserTenantDO.class);
    }

    @Test
    void homeResponseContainsResolvedBrandingAndDynamicMembership() {
        TenantMapper tenantMapper = mock(TenantMapper.class);
        UserTenantService userTenantService = mock(UserTenantService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        TenantMediaService mediaService = mock(TenantMediaService.class);
        TenantCrudServiceImpl service = new TenantCrudServiceImpl(
                tenantMapper,
                userTenantService,
                mock(UserMapper.class),
                redisTemplate,
                mock(JwtUtil.class),
                mock(NotificationService.class),
                mock(TenantValidationHelper.class),
                mock(NotificationMapper.class),
                mediaService,
                mock(CurrentTenantStateService.class));

        TenantDO tenant = new TenantDO();
        tenant.setTenantId(20L);
        tenant.setName("Verse 团队");
        tenant.setType("TEAM");
        tenant.setDescription("统一模型网关团队");
        tenant.setLogo("tenants/20/logo/logo.webp");
        tenant.setBanner("tenants/20/banner/banner.webp");
        when(redisTemplate.opsForValue().get(anyString())).thenReturn(null);
        when(userTenantService.isUserJoinedTenant(10L, 20L)).thenReturn(true);
        when(userTenantService.getRoleByUserIdAndTenantId(10L, 20L)).thenReturn("ADMIN");
        when(userTenantService.count(any())).thenReturn(6L);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        when(mediaService.resolveUrl(tenant.getLogo())).thenReturn("https://assets.example/verse/" + tenant.getLogo());
        when(mediaService.resolveUrl(tenant.getBanner())).thenReturn("https://assets.example/verse/" + tenant.getBanner());

        TenantInfoRespDTO response = service.getTenantInfo(10L, 20L);

        assertEquals("ADMIN", response.getRole());
        assertEquals(6L, response.getMemberCount());
        assertEquals("https://assets.example/verse/tenants/20/logo/logo.webp", response.getLogoUrl());
        assertEquals("https://assets.example/verse/tenants/20/banner/banner.webp", response.getBannerUrl());
    }

    @Test
    void cachedTenantDataIsOverwrittenWithCurrentUsersRole() {
        TenantMapper tenantMapper = mock(TenantMapper.class);
        UserTenantService userTenantService = mock(UserTenantService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        TenantCrudServiceImpl service = new TenantCrudServiceImpl(
                tenantMapper,
                userTenantService,
                mock(UserMapper.class),
                redisTemplate,
                mock(JwtUtil.class),
                mock(NotificationService.class),
                mock(TenantValidationHelper.class),
                mock(NotificationMapper.class),
                mock(TenantMediaService.class),
                mock(CurrentTenantStateService.class));

        when(redisTemplate.opsForValue().get(anyString())).thenReturn(
                "{\"tenantId\":\"20\",\"name\":\"Verse 团队\",\"type\":\"TEAM\",\"role\":\"SUPER_ADMIN\"}");
        when(userTenantService.isUserJoinedTenant(11L, 20L)).thenReturn(true);
        when(userTenantService.getRoleByUserIdAndTenantId(11L, 20L)).thenReturn("MEMBER");
        when(userTenantService.count(any())).thenReturn(3L);

        TenantInfoRespDTO response = service.getTenantInfo(11L, 20L);

        assertEquals("MEMBER", response.getRole());
        assertEquals(3L, response.getMemberCount());
        assertNull(response.getLogoUrl());
    }
}
