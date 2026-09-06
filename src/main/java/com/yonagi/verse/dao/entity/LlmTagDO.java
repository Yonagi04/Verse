package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_llm_tag")
public class LlmTagDO {

    /** 标签编码。 */
    @TableId
    private String tagCode;

    /** 标签显示名称。 */
    private String displayName;

    /** 标签说明。 */
    private String description;

    /** 排序序号。 */
    private Integer sortOrder;
}
