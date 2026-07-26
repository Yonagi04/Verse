package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/26 13:39
 */
@Data
public class TenantJoinReqDTO {

    @NotBlank
    private String inviteCode;
}
