package com.yonagi.verse.common.enums;

import lombok.Getter;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/29 12:31
 */
@Getter
public enum LLMProviderEnum {

    OPENAI("openai", "OpenAI", null),
    ANTHROPIC("anthropic", "Anthropic", null),
    GEMINI("gemini", "Google Gemini", new String[]{"谷歌"}),
    MISTRAL("mistral", "Mistral AI", null),
    GROK("grok", "xAI Grok", null),
    PERPLEXITY("perplexity", "Perplexity", null),
    COHERE("cohere", "Cohere", null),
    LLAMA("llama", "Meta Llama", null),

    DEEPSEEK("deepseek", "DeepSeek", new String[]{"深度求索"}),
    ZHIPU("zhipu", "智谱AI", null),
    QWEN("qwen", "通义千问", new String[]{"阿里巴巴", "千问", "Alibaba"}),
    DOUBAO("doubao", "豆包", new String[]{"火山引擎", "字节跳动", "ByteDance"}),
    KIMI("kimi", "Kimi", new String[]{"月之暗面", "Moonshot"}),
    MINIMAX("minimax", "Minimax", null),
    ERNIE("ernie", "文心一言", new String[]{"百度", "Baidu"}),
    HUNYUAN("hunyuan", "腾讯混元", new String[]{"腾讯"}),
    STEPFUN("stepfun", "阶跃星辰", null),
    YI("yi", "零一万物", null),
    BAICHUAN("baichuan", "百川智能", null),

    OPENROUTER("openrouter", "OpenRouter", null),
    SILICONFLOW("siliconflow", "硅基流动", null),
    AZURE("azure", "Azure OpenAI", new String[]{"Azure", "微软", "Microsoft"}),
    BEDROCK("bedrock", "AWS Bedrock", new String[]{"AWS", "亚马逊"}),
    OLLAMA("ollama", "Ollama", null),
    OTHER("other", "其他", null)
    ;


    private final String provider;
    private final String displayName;
    private final String[] aliases;

    LLMProviderEnum(String provider, String displayName, String[] aliases) {
        this.provider = provider;
        this.displayName = displayName;
        this.aliases = aliases;
    }

    /**
     * 根据 provider 英文标识查找对应枚举（忽略大小写），未匹配返回 null。
     */
    public static LLMProviderEnum fromProvider(String provider) {
        if (provider == null || provider.isEmpty()) {
            return null;
        }
        for (LLMProviderEnum value : values()) {
            if (value.provider.equalsIgnoreCase(provider)) {
                return value;
            }
        }
        return null;
    }
}
