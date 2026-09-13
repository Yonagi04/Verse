package com.yonagi.verse.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 租户品牌图片上传响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantMediaUploadRespDTO {

    /**
     * 图片的可访问 URL
     */
    private String url;
}
