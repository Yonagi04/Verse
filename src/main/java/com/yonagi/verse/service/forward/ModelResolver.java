package com.yonagi.verse.service.forward;

import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;

/**
 * 模型解析器 — 将租户内模型别名解析为服务配置。
 *
 * @author Yonagi
 */
public interface ModelResolver {

    /**
     * 按模型别名（仅服务别名 name）解析出服务配置。
     *
     * @param tenantId 租户 ID
     * @param model    模型别名
     * @return 服务配置（apiKey 为加密态，需自行解密）
     */
    LlmServiceDO resolve(Long tenantId, String model);

    /** 校验服务是否启用了指定操作，且有匹配的适配器。 */
    default void requireBinding(LlmServiceDO service, ModelOperation operation) {
        // 旧实现仅支持 OpenAI 兼容 Chat；用于迁移期间的兼容调用。
    }

    /** 返回已校验绑定的协议；旧实现仅支持兼容 Chat。 */
    default UpstreamProtocol protocolFor(LlmServiceDO service, ModelOperation operation) {
        requireBinding(service, operation);
        return UpstreamProtocol.OPENAI_COMPAT;
    }
}
