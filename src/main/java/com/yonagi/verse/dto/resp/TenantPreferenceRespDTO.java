package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/** 服务端保存后的租户偏好。 */
@Data
public class TenantPreferenceRespDTO {
    /** 租户业务 ID。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    /** 是否收藏。 */
    private boolean favorite;

    /** 是否置顶。 */
    private boolean pinned;
}
