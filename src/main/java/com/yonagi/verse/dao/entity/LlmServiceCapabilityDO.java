package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 模型服务的单项能力绑定。 */
@Data
@TableName("t_llm_service_capability")
public class LlmServiceCapabilityDO {
    /** 自增主键。 */
    private Long id;
    /** 模型服务业务 ID。 */
    private Long serviceId;
    /** 客户端操作。 */
    private String operation;
    /** 上游协议。 */
    private String upstreamProtocol;
    /** 是否启用。 */
    private Integer enabled;
}
