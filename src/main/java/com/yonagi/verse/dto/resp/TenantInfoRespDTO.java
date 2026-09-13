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

    /**
     * 租户业务 ID
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    /**
     * 租户名称
     */
    private String name;

    /**
     * 租户类型：PERSONAL / TEAM
     */
    private String type;

    /**
     * 租户简介
     */
    private String description;

    /**
     * 租户 Logo 的可访问 URL
     */
    private String logoUrl;

    /**
     * 租户头图的可访问 URL
     */
    private String bannerUrl;

    /**
     * 当前用户在租户内的角色
     */
    private String role;

    /**
     * 当前有效成员数量
     */
    private Long memberCount;

    /**
     * 是否开启模型调用审计：0=关闭, 1=开启
     */
    private Integer auditEnabled;
}
