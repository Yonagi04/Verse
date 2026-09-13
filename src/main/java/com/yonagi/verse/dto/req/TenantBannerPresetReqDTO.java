package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 租户内置头图选择请求。
 */
@Data
public class TenantBannerPresetReqDTO {

    /**
     * 内置头图编号：01 / 02 / 03 / 04
     */
    @NotBlank(message = "头图编号不能为空")
    @Pattern(regexp = "0[1-4]", message = "头图编号无效")
    private String presetId;
}
