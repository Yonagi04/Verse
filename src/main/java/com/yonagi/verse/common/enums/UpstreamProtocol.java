package com.yonagi.verse.common.enums;

/** 服务配置中显式选择的上游协议。 */
public enum UpstreamProtocol {
    OPENAI_COMPAT, ANTHROPIC_MESSAGES, GEMINI_GENERATE_CONTENT,
    AZURE_OPENAI_V1, AZURE_OPENAI_DEPLOYMENT, BEDROCK_CONVERSE,
    OLLAMA_NATIVE, OPENROUTER_RERANK
}
