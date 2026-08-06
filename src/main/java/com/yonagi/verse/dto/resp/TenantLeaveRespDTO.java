package com.yonagi.verse.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/06 19:57
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TenantLeaveRespDTO {

    private Long targetTenantId;
}
