package com.yonagi.verse.service;

import com.yonagi.verse.common.security.UserContext;

import java.util.List;

/**
 * LLM 转发服务 — 编排鉴权后的转发主链路（解析模型 → 解密 → 上游调用 → 记录 Token）。
 *
 * @author Yonagi
 */
public interface LlmForwardService {

    /**
     * 处理一次 chat/completions 转发请求。
     *
     * @param ctx       当前请求上下文（含 userId/tenantId/apiKeyId）
     * @param body      原始请求体（OpenAI 兼容 JSON）
     * @param requestId 请求追踪 ID
     * @return 上游响应体（OpenAI 兼容格式，含 usage）
     */
    String chatCompletion(UserContext ctx, String body, String requestId);

    /**
     * 列出当前租户下启用的模型别名（OpenAI /models 兼容）。
     *
     * @param tenantId 租户 ID
     * @return 启用模型别名列表
     */
    List<String> listModels(Long tenantId);
}
