package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** 租户动态可展示的详情，未出现在当前事件中的字段省略。 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantActivityDetailRespDTO {
    /** 发生变更的业务字段。 */
    private List<String> changedFields;
    /** 成员加入租户的方式。 */
    private String joinSource;
    /** 成员变更前的角色。 */
    private String oldRole;
    /** 成员变更后的角色。 */
    private String newRole;
    /** 邀请启用或停用后的状态。 */
    private String status;
    /** 邀请的过期时间快照。 */
    private String expiresAt;
    /** 命中的预算周期。 */
    private String period;
    /** 命中周期的起点。 */
    private String periodStart;
    /** 命中周期的结束时间。 */
    private String periodEnd;
    /** 触发预算限制的时间。 */
    private String triggeredAt;
    /** 客户端可重试的时间。 */
    private String retryAt;
    /** 模型提供商标识。 */
    private String provider;
    /** 配置的上游模型名称。 */
    private String modelName;
    /** 模型访问凭据是否发生变更。 */
    private Boolean credentialChanged;
}
