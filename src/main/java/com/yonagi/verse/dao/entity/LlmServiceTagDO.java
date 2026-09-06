package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("t_llm_service_tag")
public class LlmServiceTagDO {

    /** 自增主键。 */
    private Long id;

    /** 服务 ID。 */
    private Long serviceId;

    /** 标签编码。 */
    private String tagCode;

    /** 创建时间。 */
    private Date createTime;
}
