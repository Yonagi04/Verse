package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 租户设置完整快照更新请求。
 *
 * @author Yonagi
 */
@Data
public class TenantSettingsUpdateReqDTO {

    /** 租户名称 */
    @NotBlank(message = "租户名称不能为空")
    @Size(max = 25, message = "租户名称不能超过25个字符")
    private String name;

    /** 租户简介 */
    @Size(max = 200, message = "租户简介不能超过200个字符")
    private String description;

    /** 加入审批模式：0=直接加入，1=管理员审批 */
    @NotNull(message = "加入审批模式不能为空")
    @Min(value = 0, message = "加入审批模式无效")
    @Max(value = 1, message = "加入审批模式无效")
    private Integer joinApprovalMode;

    /** 是否开启模型调用审计 */
    @NotNull(message = "调用审计开关不能为空")
    private Boolean auditEnabled;

    /** 是否开启租户动态记录 */
    @NotNull(message = "动态记录开关不能为空")
    private Boolean activityRecordingEnabled;

    /** 租户级 RPM 上限，NULL/0 表示不限 */
    @Min(value = 0, message = "RPM 不能小于0")
    private Integer rateLimitRpm;

    /** 租户级 TPM 上限，NULL/0 表示不限 */
    @Min(value = 0, message = "TPM 不能小于0")
    private Integer rateLimitTpm;
}
