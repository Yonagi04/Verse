package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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

    /** 租户业务 ID。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    /** 租户名称。 */
    private String name;

    /** 团队或个人租户。 */
    private String type;

    /** 当前用户在目标租户中的角色。 */
    private String role;

    /** 是否为服务端权威的当前活跃租户。 */
    private boolean current;

    /** 加入时间。 */
    private Date joinedAt;

    /** 最近一次切换到该租户的时间。 */
    private Date lastAccessedAt;

    /** 当前用户是否收藏该租户。 */
    private boolean favorite;

    /** 当前用户是否将该租户置顶。 */
    private boolean pinned;
}
