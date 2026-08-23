package com.yonagi.verse.service.forward;

/**
 * 协议适配接口 — 屏蔽上游 provider 协议差异（OpenAI / Anthropic / Gemini ...）。
 * 首期仅实现 OpenAI 兼容透传，其余 provider 在此预留扩展点。
 *
 * @author Yonagi
 */
public interface ProviderAdapter {

    /**
     * 转发一次请求并返回上游原始响应体（OpenAI 兼容格式）。
     *
     * @param ctx 转发上下文
     * @return 上游响应体字符串
     */
    String forward(ForwardContext ctx);
}
