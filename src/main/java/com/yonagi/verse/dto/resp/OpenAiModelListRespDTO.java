package com.yonagi.verse.dto.resp;

import java.util.List;

/** OpenAI 兼容模型列表。 */
public record OpenAiModelListRespDTO(
        /** 列表对象类型。 */ String object,
        /** 当前租户可调用的模型。 */ List<ModelDTO> data) {

    /** 客户端用于选择模型的公开标识。 */
    public record ModelDTO(
            /** 请求中可使用的模型名称。 */ String id,
            /** 模型对象类型。 */ String object) { }
}
