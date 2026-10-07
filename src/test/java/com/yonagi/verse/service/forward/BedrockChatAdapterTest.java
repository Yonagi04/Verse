package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.ClientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BedrockChatAdapterTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void sdkCompletionRequiresMessageStopBeforeSynthesizingDone(boolean stopped) {
        var adapter = new BedrockChatAdapter();
        var client = mock(BedrockRuntimeAsyncClient.class);
        ((Map<String, BedrockRuntimeAsyncClient>) ReflectionTestUtils.getField(adapter, "streamClients")).put("us-east-1", client);
        var events = new ArrayList<ConverseStreamOutput>();
        // SDK 的事件联合构造器支持 Visitor 分发，普通 DTO 构造器不支持 accept。
        if (stopped) events.add(ConverseStreamOutput.messageStopBuilder().stopReason(StopReason.END_TURN).build());
        events.add(ConverseStreamOutput.metadataBuilder().usage(TokenUsage.builder().inputTokens(5).outputTokens(2).totalTokens(7).build()).build());
        when(client.converseStream(any(ConverseStreamRequest.class), any(ConverseStreamResponseHandler.class))).thenAnswer(call -> {
            ConverseStreamResponseHandler handler = call.getArgument(1);
            var completed = new CompletableFuture<Void>();
            handler.onEventStream(SdkPublisher.adapt(Flux.fromIterable(events)
                    .doOnComplete(() -> completed.complete(null))));
            return completed;
        });
        var context = ForwardContext.builder().modelName("model").providerSettings("{\"region\":\"us-east-1\"}")
                .body("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}").build();
        var received = new ArrayList<org.springframework.http.codec.ServerSentEvent<String>>();
        var stream = adapter.stream(context).doOnNext(received::add);
        if (stopped) assertDoesNotThrow(() -> stream.blockLast(java.time.Duration.ofSeconds(5)));
        else {
            var failure = assertThrows(UpstreamFailureException.class, () -> stream.blockLast(java.time.Duration.ofSeconds(5)));
            assertEquals(UpstreamExecutionOutcome.UNKNOWN, failure.getExecutionOutcome());
            assertFalse(failure.isRetryable());
        }
        assertEquals(stopped ? 1 : 0, received.stream().filter(e -> "[DONE]".equals(e.data())).count());
        assertTrue(received.stream().anyMatch(e -> e.data().contains("total_tokens")));
        verify(client, times(1)).converseStream(any(ConverseStreamRequest.class), any(ConverseStreamResponseHandler.class));
    }

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
