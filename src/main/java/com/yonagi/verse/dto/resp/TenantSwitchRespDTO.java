package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/26 18:43
 */
@Data
public class TenantSwitchRespDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private String name;

    private String type;

    private String role;
}
