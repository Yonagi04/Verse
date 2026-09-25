package com.yonagi.verse.dto.resp;

import lombok.Data;

/**
 * 租户设置快照响应。
 *
 * @author Yonagi
 */
@Data
public class TenantSettingsRespDTO {

    /** 租户业务 ID */
    private Long tenantId;

    /** 租户类型：PERSONAL / TEAM */
    private String type;

    /** 租户名称 */
    private String name;

    /** 租户简介 */
    private String description;

    /** 加入审批模式：0=直接加入，1=管理员审批 */
    private Integer joinApprovalMode;

    /** 是否开启模型调用审计 */
    private Boolean auditEnabled;

    /** 是否开启租户动态记录 */
    private Boolean activityRecordingEnabled;

    /** 是否开启 Playground 功能 */
    private Boolean playgroundEnabled;

    /** 租户级 RPM 上限，NULL 表示不限 */
    private Integer rateLimitRpm;

    /** 租户级 TPM 上限，NULL 表示不限 */
    private Integer rateLimitTpm;

    /** 当前用户在目标租户中的角色 */
    private String role;

    /** 当前用户是否可编辑设置 */
    private Boolean editable;
}
