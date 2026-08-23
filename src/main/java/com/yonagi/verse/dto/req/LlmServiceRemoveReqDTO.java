package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/23 11:55
 */
@Data
public class LlmServiceRemoveReqDTO {

    @NotBlank(message = "token不能为空")
    private String token;
}
