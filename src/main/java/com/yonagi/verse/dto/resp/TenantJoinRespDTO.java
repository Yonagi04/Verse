package com.yonagi.verse.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/05 20:25
 */
@Data
@AllArgsConstructor
public class TenantJoinRespDTO {

    // 是否需要审批, true=需要审批, false=不需要审批
    private Boolean pendingApproval;
}
