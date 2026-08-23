package com.yonagi.verse.service.forward;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 转发上下文 — 一次上游调用的必要信息，抽象出 provider 协议差异。
 *
 * @author Yonagi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForwardContext {

    /**
     * 上游 API 地址（如 https://api.openai.com/v1）
     */
    private String apiUrl;

    /**
     * 上游真实 API Key（已 AES 解密）
     */
    private String apiKey;

    /**
     * 上游模型名（替换请求体中的 model 字段）
     */
    private String modelName;

    /**
     * 原始请求体（OpenAI 兼容 JSON）
     */
    private String body;
}
