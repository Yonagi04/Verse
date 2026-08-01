package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/25 11:53
 */
@Data
public class TenantInfoRespDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private String name;

    private String type;

    private String description;
}
