package com.yonagi.verse.service.usage;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.service.forward.StreamResponseAccumulator;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.CostStatus;
import com.yonagi.verse.common.enums.PricePeriodType;
import com.yonagi.verse.service.pricing.CostCalculator;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class UsageNormalizationTest {
    private UsageNormalizerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new UsageNormalizerRegistry(List.of(
                new OpenAiUsageNormalizer(), new AnthropicUsageNormalizer(), new GeminiUsageNormalizer(),
                new DeepSeekUsageNormalizer(), new ZhipuUsageNormalizer(), new QwenUsageNormalizer(),
                new DoubaoUsageNormalizer(), new KimiUsageNormalizer(), new MiniMaxUsageNormalizer(),
                new OpenAiCompatibleUsageNormalizer()));
    }

    @Test
    void openAiChatIncludesCachedTokensWithoutDoubleCounting() {
        UsageBreakdown usage = normalize("openai", """
                {"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,
                "prompt_tokens_details":{"cached_tokens":40}}}
                """);
        assertTrue(usage.valid());
        assertEquals("openai", usage.parser());
        assertEquals(100L, usage.inputTokens());
        assertEquals(40L, usage.cachedInputTokens());
    }

    @Test
    void anthropicSeparatelyReportedCacheIsIncludedInInput() {
        UsageBreakdown usage = normalize("anthropic", """
                {"usage":{"input_tokens":10,"cache_creation_input_tokens":20,
                "cache_read_input_tokens":30,"output_tokens":5}}
                """);
        assertTrue(usage.valid());
        assertEquals("anthropic", usage.parser());
        assertEquals(60L, usage.inputTokens());
        assertEquals(30L, usage.cachedInputTokens());
        assertEquals(20L, usage.cacheWriteInputTokens());
    }

    @Test
    void geminiIncludesThoughtTokensAndKeepsUsageMetadata() {
        UsageBreakdown usage = normalize("gemini", """
                {"usageMetadata":{"promptTokenCount":20,"cachedContentTokenCount":5,
                "candidatesTokenCount":4,"thoughtsTokenCount":6,"totalTokenCount":30}}
                """);
        assertTrue(usage.valid());
        assertEquals("gemini", usage.parser());
        assertEquals(10L, usage.outputTokens());
        assertTrue(usage.rawUsage().containsKey("thoughtsTokenCount"));
    }

    @Test
    void deepSeekCrossChecksHitAndMiss() {
        UsageBreakdown usage = normalize("deepseek", """
                {"usage":{"prompt_cache_hit_tokens":8,"prompt_cache_miss_tokens":4,
                "prompt_tokens":12,"completion_tokens":2,"total_tokens":14}}
                """);
        assertTrue(usage.valid());
        assertEquals("deepseek", usage.parser());
        assertEquals(8L, usage.cachedInputTokens());
    }

    @Test
    void chineseCoreProviderDedicatedFixturesAreRecognized() {
        assertFixture("zhipu", """
                {"usage":{"prompt_tokens":9,"completion_tokens":3,"total_tokens":12,
                "prompt_tokens_details":{"cached_tokens":2}}}
                """);
        assertFixture("qwen", """
                {"usage":{"input_tokens":9,"output_tokens":3,"total_tokens":12,
                "input_tokens_details":{"cached_tokens":2}}}
                """);
        assertFixture("doubao", """
                {"usage":{"input_tokens":9,"output_tokens":3,"total_tokens":12,
                "input_tokens_details":{"cached_tokens":2}}}
                """);
        assertFixture("kimi", """
                {"usage":{"prompt_tokens":9,"completion_tokens":3,"total_tokens":12,"cached_tokens":2}}
                """);
        assertFixture("minimax", """
                {"usage":{"prompt_tokens":9,"completion_tokens":3,"total_tokens":12,"cache_tokens":2}}
                """);
    }

    @Test
    void coreProviderCompatibleShapeFallsBackToCommonParser() {
        UsageBreakdown usage = normalize("anthropic", """
                {"usage":{"prompt_tokens":9,"completion_tokens":3,"total_tokens":12}}
                """);
        assertTrue(usage.valid());
        assertEquals("openai-compatible", usage.parser());
    }

    @Test
    void nonCoreProviderEvaluatesOnlyCommonParser() {
        UsageBreakdown compatible = normalize("openrouter", """
                {"usage":{"prompt_tokens":9,"completion_tokens":3,"total_tokens":12}}
                """);
        assertTrue(compatible.valid());
        assertEquals("openai-compatible", compatible.parser());

        UsageBreakdown nativeGemini = normalize("openrouter", """
                {"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":3,"totalTokenCount":12}}
                """);
        assertFalse(nativeGemini.valid());
        assertEquals("openai-compatible", nativeGemini.parser());
    }

    @Test
    void invalidDistinctiveShapeDoesNotFallBackAndMaskError() {
        UsageBreakdown missing = normalize("deepseek", """
                {"usage":{"prompt_cache_hit_tokens":8,"prompt_tokens":12,
                "completion_tokens":2,"total_tokens":14}}
                """);
        assertFalse(missing.valid());
        assertEquals("deepseek", missing.parser());

        assertFalse(normalize("openai", """
                {"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":3}}
                """).valid());
        assertFalse(normalize("kimi", """
                {"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12,"cached_tokens":11}}
                """).valid());
        assertFalse(normalize("qwen", """
                {"usage":{"input_tokens":"bad","output_tokens":2,"total_tokens":2}}
                """).valid());
        assertFalse(normalize("anthropic", """
                {"usage":{"input_tokens":-1,"output_tokens":2}}
                """).valid());
    }

    @Test
    void streamRetainsExactTerminalUsageEnvelope() {
        StreamResponseAccumulator accumulator = new StreamResponseAccumulator(false);
        accumulator.accept("{\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":2,\"totalTokenCount\":7}}");
        assertNotNull(accumulator.usageEnvelope().getJSONObject("usageMetadata"));
        assertTrue(registry.normalize("gemini", accumulator.usageEnvelope()).valid());

        StreamResponseAccumulator malformed = new StreamResponseAccumulator(false);
        malformed.accept("{\"usageMetadata\":\"bad\"}");
        assertNotNull(malformed.usageEnvelope());
        assertFalse(registry.normalize("gemini", malformed.usageEnvelope()).valid());

        StreamResponseAccumulator missing = new StreamResponseAccumulator(false);
        missing.accept("not-json");
        assertNull(missing.usageEnvelope());
    }

    @ParameterizedTest
    @MethodSource("coreProviderSamples")
    void everyCoreProviderProducesItsParserAndCalculatedCost(String provider, String json) {
        UsageBreakdown usage = normalize(provider, json);
        PricingSnapshot pricing = new PricingSnapshot(1L, BillingMode.TOKEN, "CNY", PricePeriodType.BASE,
                null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, Instant.EPOCH, null);
        assertEquals(provider, usage.parser());
        assertEquals(CostStatus.CALCULATED,
                new CostCalculator().calculate("SUCCESS", usage, pricing).status());
    }

    @Test
    void invalidCoreShapeProducesUncalculableCost() {
        UsageBreakdown usage = normalize("deepseek", """
                {"usage":{"prompt_cache_hit_tokens":8,"prompt_tokens":12,"completion_tokens":2}}
                """);
        PricingSnapshot pricing = new PricingSnapshot(1L, BillingMode.TOKEN, "CNY", PricePeriodType.BASE,
                null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, Instant.EPOCH, null);
        assertEquals(CostStatus.UNCALCULABLE,
                new CostCalculator().calculate("SUCCESS", usage, pricing).status());
    }

    static Stream<Arguments> coreProviderSamples() {
        return Stream.of(
                Arguments.of("openai", "{\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3,\"total_tokens\":12}}"),
                Arguments.of("anthropic", "{\"usage\":{\"input_tokens\":9,\"output_tokens\":3}}"),
                Arguments.of("gemini", "{\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":3,\"totalTokenCount\":12}}"),
                Arguments.of("deepseek", "{\"usage\":{\"prompt_cache_hit_tokens\":2,\"prompt_cache_miss_tokens\":7,\"completion_tokens\":3}}"),
                Arguments.of("zhipu", "{\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3,\"prompt_tokens_details\":{\"cached_tokens\":2}}}"),
                Arguments.of("qwen", "{\"usage\":{\"input_tokens\":9,\"output_tokens\":3}}"),
                Arguments.of("doubao", "{\"usage\":{\"input_tokens\":9,\"output_tokens\":3}}"),
                Arguments.of("kimi", "{\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3,\"cached_tokens\":2}}"),
                Arguments.of("minimax", "{\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3,\"cache_tokens\":2}}")
        );
    }

    private UsageBreakdown normalize(String provider, String json) {
        return registry.normalize(provider, JSON.parseObject(json));
    }

    private void assertFixture(String provider, String json) {
        UsageBreakdown usage = normalize(provider, json);
        assertTrue(usage.valid(), provider);
        assertEquals(provider, usage.parser());
        assertEquals(2L, usage.cachedInputTokens());
    }
}
