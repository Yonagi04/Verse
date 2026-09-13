package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dto.resp.TenantMediaUploadRespDTO;
import com.yonagi.verse.service.UserTenantService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantMediaServiceImplTest {

    private final S3Client s3Client = mock(S3Client.class);
    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    private final UserTenantService userTenantService = mock(UserTenantService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    private TenantMediaServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "tenant-media"),
                TenantDO.class);
    }

    @BeforeEach
    void setUp() {
        service = new TenantMediaServiceImpl(s3Client, tenantMapper, userTenantService, redisTemplate);
        ReflectionTestUtils.setField(service, "bucket", "verse");
        ReflectionTestUtils.setField(service, "baseUrl", "https://assets.example/");
    }

    @Test
    void adminCanUploadAndPersistBanner() throws Exception {
        TenantDO tenant = new TenantDO();
        tenant.setTenantId(20L);
        when(userTenantService.isUserJoinedTenant(10L, 20L)).thenReturn(true);
        when(userTenantService.getRoleByUserIdAndTenantId(10L, 20L)).thenReturn("ADMIN");
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        when(tenantMapper.update(any(Wrapper.class))).thenReturn(1);

        MockMultipartFile file = new MockMultipartFile(
                "file", "banner.png", "image/png", createPng(800, 300));

        TenantMediaUploadRespDTO response = service.uploadBanner(10L, 20L, file);

        assertTrue(response.getUrl().startsWith("https://assets.example/verse/tenants/20/banner/"));
        assertTrue(response.getUrl().endsWith(".webp"));
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(redisTemplate).delete("verse:tenant:info:20");
    }

    @Test
    void memberCannotUploadBranding() {
        when(userTenantService.isUserJoinedTenant(10L, 20L)).thenReturn(true);
        when(userTenantService.getRoleByUserIdAndTenantId(10L, 20L)).thenReturn("MEMBER");
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1});

        ClientException exception = assertThrows(ClientException.class,
                () -> service.uploadLogo(10L, 20L, file));

        assertEquals(TenantErrorCodeEnum.TENANT_PERMISSION_DENIED.code(), exception.getErrorCode());
    }

    @Test
    void rejectsUnsupportedContentType() {
        TenantDO tenant = new TenantDO();
        when(userTenantService.isUserJoinedTenant(10L, 20L)).thenReturn(true);
        when(userTenantService.getRoleByUserIdAndTenantId(10L, 20L)).thenReturn("ADMIN");
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        MockMultipartFile file = new MockMultipartFile("file", "logo.gif", "image/gif", new byte[]{1});

        ClientException exception = assertThrows(ClientException.class,
                () -> service.uploadLogo(10L, 20L, file));

        assertEquals(TenantErrorCodeEnum.TENANT_MEDIA_TYPE_INVALID.code(), exception.getErrorCode());
    }

    @Test
    void adminCanSelectBuiltInBannerPreset() {
        TenantDO tenant = new TenantDO();
        tenant.setTenantId(20L);
        tenant.setBanner("tenants/20/banner/old.webp");
        when(userTenantService.isUserJoinedTenant(10L, 20L)).thenReturn(true);
        when(userTenantService.getRoleByUserIdAndTenantId(10L, 20L)).thenReturn("ADMIN");
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        when(tenantMapper.update(any(Wrapper.class))).thenReturn(1);

        TenantMediaUploadRespDTO response = service.selectBannerPreset(10L, 20L, "03");

        assertEquals("/images/tenant-cover-03.png", response.getUrl());
        verify(redisTemplate).delete("verse:tenant:info:20");
    }

    @Test
    void rejectsUnknownBannerPreset() {
        TenantDO tenant = new TenantDO();
        when(userTenantService.isUserJoinedTenant(10L, 20L)).thenReturn(true);
        when(userTenantService.getRoleByUserIdAndTenantId(10L, 20L)).thenReturn("ADMIN");
        when(tenantMapper.selectOne(any())).thenReturn(tenant);

        ClientException exception = assertThrows(ClientException.class,
                () -> service.selectBannerPreset(10L, 20L, "05"));

        assertEquals(TenantErrorCodeEnum.TENANT_BANNER_PRESET_INVALID.code(), exception.getErrorCode());
    }

    private byte[] createPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(22, 119, 255));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
