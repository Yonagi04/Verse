package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/01 11:26
 */
@Data
public class TenantMemberRoleUpdateReqDTO {

    @NotBlank
    private String newRole;
}
