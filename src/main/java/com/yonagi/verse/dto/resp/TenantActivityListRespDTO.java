package com.yonagi.verse.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 租户动态游标列表响应。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantActivityListRespDTO {

    /** 当前批次动态项。 */
    private List<TenantActivityItemRespDTO> items;

    /** 下一批游标，无后续数据时为空。 */
    private String nextCursor;

    /** 是否仍有后续动态。 */
    private Boolean hasMore;
}
