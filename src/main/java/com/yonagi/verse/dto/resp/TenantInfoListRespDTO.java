package com.yonagi.verse.dto.resp;

import lombok.Data;

import java.util.Date;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/19 20:40
 */
@Data
public class TenantInfoListRespDTO {

    private Long tenantId;

    private String name;

    private String type;

    private String role;

    private Date joinedAt;

    private Date lastAccessedAt;
}
