package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 当前用户对目标租户的收藏与置顶设置。 */
@Data
public class TenantPreferenceReqDTO {
    /** 是否收藏目标租户。 */
    @NotNull
    private Boolean favorite;

    /** 是否置顶目标租户，与收藏状态独立。 */
    @NotNull
    private Boolean pinned;
}
