package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/25 18:24
 */
@Data
public class ConfirmCloseAccountReqDTO {

    @NotBlank(message = "验证码不能为空")
    private String code;
}
