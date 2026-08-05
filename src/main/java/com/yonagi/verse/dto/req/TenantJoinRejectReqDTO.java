package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/05 20:17
 */
@Data
public class TenantJoinRejectReqDTO {

    @Size(max = 255, message = "审批理由不能超过255个字符")
    private String reviewComment;
}
