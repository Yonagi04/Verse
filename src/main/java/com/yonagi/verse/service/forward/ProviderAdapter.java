package com.yonagi.verse.service.forward;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

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

    /**
     * 转发一次流式请求（stream=true），返回上游 SSE 事件流。
     *
     * @param ctx 转发上下文
     * @return 上游 SSE 事件流（每个元素为解析后的事件，data 为原始字符串）
     */
    Flux<ServerSentEvent<String>> stream(ForwardContext ctx);
}
