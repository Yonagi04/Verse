package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/19 20:55
 */
@Data
public class TenantCreateReqDTO {

    @NotBlank(message = "租户名称不能为空")
    @Length(max = 25, message = "租户名称不能超过25个字符")
    private String name;

    @Length(max = 200, message = "租户描述不能超过200个字符")
    private String description;
}
