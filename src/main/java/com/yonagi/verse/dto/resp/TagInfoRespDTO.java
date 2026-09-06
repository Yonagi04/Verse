package com.yonagi.verse.dto.resp;

import lombok.Data;

@Data
public class TagInfoRespDTO {

    /** 标签编码。 */
    private String code;

    /** 标签显示名称。 */
    private String displayName;

    /** 标签说明。 */
    private String description;

    /** 排序序号。 */
    private Integer sortOrder;
}
