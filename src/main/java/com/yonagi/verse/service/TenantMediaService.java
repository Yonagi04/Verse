package com.yonagi.verse.service;

import com.yonagi.verse.dto.resp.TenantMediaUploadRespDTO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 租户品牌图片服务。
 */
public interface TenantMediaService {

    TenantMediaUploadRespDTO uploadLogo(Long userId, Long tenantId, MultipartFile file);

    TenantMediaUploadRespDTO uploadBanner(Long userId, Long tenantId, MultipartFile file);

    TenantMediaUploadRespDTO selectBannerPreset(Long userId, Long tenantId, String presetId);

    String resolveUrl(String objectKey);
}
