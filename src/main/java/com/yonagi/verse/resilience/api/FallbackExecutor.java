package com.yonagi.verse.resilience.api;

import com.yonagi.verse.dao.entity.LlmServiceDO;

/**
 * 降级执行器 — 解析主模型配置的备用模型（单级 failover），业务只依赖此接口，实现可替换。
 *
 * @author Yonagi
 */
public interface FallbackExecutor {

    /**
     * 解析主模型的备用模型。
     *
     * @param primary 主模型配置
     * @return 备用模型配置；未配置 {@code fallbackServiceId} 或备用模型不可用（不存在/禁用/删除）时返回 null
     */
    LlmServiceDO resolveFallback(LlmServiceDO primary);
}
