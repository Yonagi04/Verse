package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dto.resp.TenantMediaUploadRespDTO;
import com.yonagi.verse.service.TenantMediaService;
import com.yonagi.verse.service.UserTenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * 租户品牌图片服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantMediaServiceImpl implements TenantMediaService {

    private static final Set<String> ALLOWED_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final Set<String> BANNER_PRESET_IDS = Set.of("01", "02", "03", "04");
    private static final String PRESET_PREFIX = "preset:";
    private static final long MAX_SIZE = 5 * 1024 * 1024;
    private static final int LOGO_SIZE = 256;
    private static final int BANNER_WIDTH = 1600;
    private static final int BANNER_HEIGHT = 400;

    private final S3Client s3Client;
    private final TenantMapper tenantMapper;
    private final UserTenantService userTenantService;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${verse.s3.bucket}")
    private String bucket;

    @Value("${verse.s3.base-url}")
    private String baseUrl;

    @Override
    public TenantMediaUploadRespDTO uploadLogo(Long userId, Long tenantId, MultipartFile file) {
        return upload(userId, tenantId, file, MediaKind.LOGO);
    }

    @Override
    public TenantMediaUploadRespDTO uploadBanner(Long userId, Long tenantId, MultipartFile file) {
        return upload(userId, tenantId, file, MediaKind.BANNER);
    }

    @Override
    public TenantMediaUploadRespDTO selectBannerPreset(Long userId, Long tenantId, String presetId) {
        TenantDO tenant = validatePermission(userId, tenantId);
        if (!BANNER_PRESET_IDS.contains(presetId)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_BANNER_PRESET_INVALID);
        }

        String presetKey = PRESET_PREFIX + presetId;
        int updated = tenantMapper.update(Wrappers.lambdaUpdate(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0)
                .set(TenantDO::getBanner, presetKey));
        if (updated < 1) {
            throw new ServerException(TenantErrorCodeEnum.TENANT_MEDIA_UPDATE_ERROR);
        }

        stringRedisTemplate.delete(RedisKeyConstant.TENANT_INFO_KEY + tenantId);
        deleteQuietly(tenant.getBanner());
        return new TenantMediaUploadRespDTO(resolveUrl(presetKey));
    }

    @Override
    public String resolveUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        if (objectKey.startsWith(PRESET_PREFIX)) {
            String presetId = objectKey.substring(PRESET_PREFIX.length());
            if (!BANNER_PRESET_IDS.contains(presetId)) {
                return null;
            }
            return "/images/tenant-cover-" + presetId + ".png";
        }
        return stripTrailingSlash(baseUrl) + "/" + bucket + "/" + objectKey;
    }

    private TenantMediaUploadRespDTO upload(Long userId, Long tenantId, MultipartFile file, MediaKind mediaKind) {
        TenantDO tenant = validatePermission(userId, tenantId);
        validateFile(file);

        byte[] processed = processImage(file, mediaKind);
        String objectKey = "tenants/" + tenantId + "/" + mediaKind.path + "/" + UUID.randomUUID() + ".webp";
        putObject(objectKey, processed);

        LambdaUpdateWrapper<TenantDO> updateWrapper = Wrappers.lambdaUpdate(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0);
        if (mediaKind == MediaKind.LOGO) {
            updateWrapper.set(TenantDO::getLogo, objectKey);
        } else {
            updateWrapper.set(TenantDO::getBanner, objectKey);
        }

        int updated = tenantMapper.update(updateWrapper);
        if (updated < 1) {
            deleteQuietly(objectKey);
            throw new ServerException(TenantErrorCodeEnum.TENANT_MEDIA_UPDATE_ERROR);
        }

        stringRedisTemplate.delete(RedisKeyConstant.TENANT_INFO_KEY + tenantId);
        String oldObjectKey = mediaKind == MediaKind.LOGO ? tenant.getLogo() : tenant.getBanner();
        deleteQuietly(oldObjectKey);
        return new TenantMediaUploadRespDTO(resolveUrl(objectKey));
    }

    private TenantDO validatePermission(Long userId, Long tenantId) {
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        if (!userTenantService.isUserJoinedTenant(userId, tenantId)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        String role = userTenantService.getRoleByUserIdAndTenantId(userId, tenantId);
        if (!RoleEnum.SUPER_ADMIN.name().equals(role) && !RoleEnum.ADMIN.name().equals(role)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        }
        TenantDO tenant = tenantMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0));
        if (tenant == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        }
        return tenant;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty() || !ALLOWED_TYPES.contains(file.getContentType())) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEDIA_TYPE_INVALID);
        }
        if (file.getSize() > MAX_SIZE) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_MEDIA_SIZE_EXCEED);
        }
    }

    private byte[] processImage(MultipartFile file, MediaKind mediaKind) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (mediaKind == MediaKind.LOGO) {
                Thumbnails.of(file.getInputStream())
                        .size(LOGO_SIZE, LOGO_SIZE)
                        .crop(Positions.CENTER)
                        .outputFormat("webp")
                        .outputQuality(0.88)
                        .toOutputStream(output);
            } else {
                Thumbnails.of(file.getInputStream())
                        .size(BANNER_WIDTH, BANNER_HEIGHT)
                        .crop(Positions.CENTER)
                        .outputFormat("webp")
                        .outputQuality(0.88)
                        .toOutputStream(output);
            }
            return output.toByteArray();
        } catch (IOException | RuntimeException e) {
            log.warn("处理租户图片失败: tenant media kind={}", mediaKind, e);
            throw new ServerException(TenantErrorCodeEnum.TENANT_MEDIA_PROCESS_ERROR);
        }
    }

    private void putObject(String objectKey, byte[] content) {
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType("image/webp")
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (Exception e) {
            log.error("上传租户图片失败: objectKey={}", objectKey, e);
            throw new ServerException(TenantErrorCodeEnum.TENANT_MEDIA_UPLOAD_ERROR);
        }
    }

    private void deleteQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.startsWith(PRESET_PREFIX)) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (Exception e) {
            log.warn("删除旧租户图片失败，将由对象存储生命周期清理: objectKey={}", objectKey, e);
        }
    }

    private String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private enum MediaKind {
        LOGO("logo"),
        BANNER("banner");

        private final String path;

        MediaKind(String path) {
            this.path = path;
        }
    }
}
