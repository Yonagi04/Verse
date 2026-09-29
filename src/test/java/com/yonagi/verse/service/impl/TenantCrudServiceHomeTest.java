package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.projection.CurrentTenantState;
import com.yonagi.verse.dao.mapper.NotificationMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.resp.TenantInfoRespDTO;
import com.yonagi.verse.dto.resp.TenantInfoListRespDTO;
import com.yonagi.verse.service.NotificationService;
import com.yonagi.verse.service.CurrentTenantStateService;
import com.yonagi.verse.service.TenantMediaService;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.helper.TenantValidationHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

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
        CurrentTenantStateService currentState = mock(CurrentTenantStateService.class);
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
                currentState,
                mock(TenantActivityRecorder.class));

        TenantDO tenant = new TenantDO();
        tenant.setTenantId(20L);
        tenant.setName("Verse 团队");
        tenant.setType("TEAM");
        tenant.setDescription("统一模型网关团队");
        tenant.setLogo("tenants/20/logo/logo.webp");
        tenant.setBanner("tenants/20/banner/banner.webp");
        when(redisTemplate.opsForValue().get(anyString())).thenReturn(null);
        UserTenantDO membership = new UserTenantDO();
        membership.setRole("ADMIN");
        when(userTenantService.getOne(any())).thenReturn(membership);
        when(userTenantService.count(any())).thenReturn(6L);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        when(mediaService.resolveUrl(tenant.getLogo())).thenReturn("https://assets.example/verse/" + tenant.getLogo());
        when(mediaService.resolveUrl(tenant.getBanner())).thenReturn("https://assets.example/verse/" + tenant.getBanner());

        TenantInfoRespDTO response = service.getTenantInfo(10L, 20L);

        assertEquals("ADMIN", response.getRole());
        assertEquals(6L, response.getMemberCount());
        assertEquals("https://assets.example/verse/tenants/20/logo/logo.webp", response.getLogoUrl());
        assertEquals("https://assets.example/verse/tenants/20/banner/banner.webp", response.getBannerUrl());
        verify(currentState, never()).switchTenant(any(), any());
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
                mock(CurrentTenantStateService.class),
                mock(TenantActivityRecorder.class));

        when(redisTemplate.opsForValue().get(anyString())).thenReturn(
                "{\"tenantId\":\"20\",\"name\":\"Verse 团队\",\"type\":\"TEAM\",\"role\":\"SUPER_ADMIN\"}");
        TenantDO tenant = new TenantDO();
        tenant.setTenantId(20L);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        UserTenantDO membership = new UserTenantDO();
        membership.setRole("MEMBER");
        when(userTenantService.getOne(any())).thenReturn(membership);
        when(userTenantService.count(any())).thenReturn(3L);

        TenantInfoRespDTO response = service.getTenantInfo(11L, 20L);

        assertEquals("MEMBER", response.getRole());
        assertEquals(3L, response.getMemberCount());
        assertNull(response.getLogoUrl());
    }

    @Test
    void listReturnsPersistedFavoriteAndPinForEachMembership() {
        TenantMapper tenantMapper = mock(TenantMapper.class);
        UserTenantService userTenantService = mock(UserTenantService.class);
        CurrentTenantStateService currentState = mock(CurrentTenantStateService.class);
        TenantCrudServiceImpl service = new TenantCrudServiceImpl(
                tenantMapper, userTenantService, mock(UserMapper.class), mock(StringRedisTemplate.class),
                mock(JwtUtil.class), mock(NotificationService.class), mock(TenantValidationHelper.class),
                mock(NotificationMapper.class), mock(TenantMediaService.class), currentState,
                mock(TenantActivityRecorder.class));
        when(currentState.resolveCurrentTenant(10L)).thenReturn(new CurrentTenantState().setTenantId(20L));
        UserTenantDO membership = new UserTenantDO();
        membership.setTenantId(20L);
        membership.setRole("ADMIN");
        membership.setFavorite(true);
        membership.setPinned(true);
        when(userTenantService.getUserTenantList(10L, Boolean.FALSE, 10L)).thenReturn(List.of(membership));
        TenantDO tenant = new TenantDO();
        tenant.setTenantId(20L);
        tenant.setName("Verse 团队");
        when(tenantMapper.selectList(any())).thenReturn(List.of(tenant));

        TenantInfoListRespDTO item = service.listTenants(10L).get(0);

        assertEquals(true, item.isFavorite());
        assertEquals(true, item.isPinned());
    }

    @Test
    void cachedDetailCannotBeReadAfterMembershipRemoval() {
        TenantMapper tenantMapper = mock(TenantMapper.class);
        UserTenantService userTenantService = mock(UserTenantService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        CurrentTenantStateService currentState = mock(CurrentTenantStateService.class);
        TenantCrudServiceImpl service = new TenantCrudServiceImpl(
                tenantMapper, userTenantService, mock(UserMapper.class), redisTemplate,
                mock(JwtUtil.class), mock(NotificationService.class), mock(TenantValidationHelper.class),
                mock(NotificationMapper.class), mock(TenantMediaService.class), currentState,
                mock(TenantActivityRecorder.class));
        TenantDO tenant = new TenantDO();
        tenant.setTenantId(20L);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        when(redisTemplate.opsForValue().get(anyString())).thenReturn("{\"tenantId\":\"20\"}");

        assertThrows(ClientException.class, () -> service.getTenantInfo(10L, 20L));
        verify(currentState, never()).switchTenant(any(), any());
        verify(redisTemplate.opsForValue(), never()).get(anyString());
    }

    @Test
    void cachedDetailCannotBeReadAfterTenantIsDisabled() {
        TenantMapper tenantMapper = mock(TenantMapper.class);
        UserTenantService userTenantService = mock(UserTenantService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        TenantCrudServiceImpl service = new TenantCrudServiceImpl(
                tenantMapper, userTenantService, mock(UserMapper.class), redisTemplate,
                mock(JwtUtil.class), mock(NotificationService.class), mock(TenantValidationHelper.class),
                mock(NotificationMapper.class), mock(TenantMediaService.class),
                mock(CurrentTenantStateService.class), mock(TenantActivityRecorder.class));
        when(redisTemplate.opsForValue().get(anyString())).thenReturn("{\"tenantId\":\"20\"}");

        assertThrows(ClientException.class, () -> service.getTenantInfo(10L, 20L));
        verify(userTenantService, never()).getOne(any());
        verify(redisTemplate.opsForValue(), never()).get(anyString());
    }
}
