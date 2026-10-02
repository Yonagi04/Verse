package com.yonagi.verse.dto.req;
import lombok.Data;
import jakarta.validation.constraints.*;
@Data
public class ExternalReauthReqDTO {
    /** 当前Verse密码 */
    @NotBlank @Size(max=72)
    private String password;
    /** 安全操作用途 */
    @NotBlank @Pattern(regexp="BIND|UNBIND|ATTACH")
    private String action;
    /** 平台 */
    @Pattern(regexp="google|github|gitlab")
    private String provider;
    /** 解绑关系 */
    @Pattern(regexp="[0-9]{1,19}")
    private String bindingId;
    /** 待验证流程 */
    @Pattern(regexp="[a-f0-9]{32}")
    private String flowId;
}
