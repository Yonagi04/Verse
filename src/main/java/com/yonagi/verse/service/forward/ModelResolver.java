package com.yonagi.verse.service.forward;

import com.yonagi.verse.dao.entity.LlmServiceDO;

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
}
