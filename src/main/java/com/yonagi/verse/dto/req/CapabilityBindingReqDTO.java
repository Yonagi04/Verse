package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 模型能力绑定请求。 */
@Data
public class CapabilityBindingReqDTO {
    /** 客户端操作。 */
    @NotNull(message = "客户端操作不能为空")
    private String operation;
    /** 上游协议。 */
    @NotNull(message = "上游协议不能为空")
    private String upstreamProtocol;
    /** 是否启用，默认启用。 */
    private Boolean enabled;
}
