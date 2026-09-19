package com.yonagi.verse.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 租户动态记录功能状态响应。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantActivityStatusRespDTO {

    /** 当前是否开启租户动态记录。 */
    private Boolean enabled;
}
