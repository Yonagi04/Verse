package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.ClientException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

import static org.junit.jupiter.api.Assertions.*;

class BedrockChatAdapterTest {
    @Test void mapsSystemToolsRegionIndependentRequestAndUsage() {
        BedrockChatAdapter adapter = new BedrockChatAdapter();
        ForwardContext context = ForwardContext.builder().modelName("anthropic.claude-model")
                .providerSettings("{\"region\":\"us-east-1\"}").build();
        JSONObject input = JSON.parseObject("""
                {"model":"alias","messages":[{"role":"system","content":"rules"},
                {"role":"user","content":"hello"}],
                "tools":[{"type":"function","function":{"name":"lookup",
                "parameters":{"type":"object","properties":{"key":{"type":"string"}}}}}],
                "max_tokens":128}
                """);
        var request = adapter.request(context, input);
        assertEquals("anthropic.claude-model", request.modelId());
        assertEquals("rules", request.system().getFirst().text());
        assertEquals("hello", request.messages().getFirst().content().getFirst().text());
        assertEquals("lookup", request.toolConfig().tools().getFirst().toolSpec().name());
        assertEquals(128, request.inferenceConfig().maxTokens());

        var upstream = ConverseResponse.builder()
                .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText("done")).build()))
                .stopReason(StopReason.END_TURN)
                .usage(TokenUsage.builder().inputTokens(6).outputTokens(2).totalTokens(8).build())
                .build();
        JSONObject result = adapter.response(context, upstream);
        assertEquals("done", result.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content"));
        assertEquals(8, result.getJSONObject("usage").getIntValue("total_tokens"));
    }

    @Test void rejectsUnsupportedContentBeforeAwsInvocation() {
        BedrockChatAdapter adapter = new BedrockChatAdapter();
        ForwardContext context = ForwardContext.builder().modelName("m").build();
        JSONObject input = JSON.parseObject("""
                {"messages":[{"role":"user","content":[{"type":"image_url"}]}]}
                """);
        assertThrows(ClientException.class, () -> adapter.request(context, input));
    }
}
