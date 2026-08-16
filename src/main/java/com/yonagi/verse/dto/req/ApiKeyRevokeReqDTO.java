package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/16 22:05
 */
@Data
public class ApiKeyRevokeReqDTO {

    @NotBlank(message = "Api Key ID不能为空")
    private String apiKeyId;
}
