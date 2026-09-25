package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolAdapterFixtureTest {
    private HttpServer server;
    private String base;
    private final AtomicInteger calls = new AtomicInteger();
    private String path;
    private String authorization;
    private String apiKey;
    private String requestBody;
    private String reply;
    private String responseType;
    private int responseStatus;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        reply = "{}";
        responseType = "application/json";
        responseStatus = 200;
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            path = exchange.getRequestURI().toString();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            apiKey = exchange.getRequestHeaders().getFirst("x-api-key");
            if (apiKey == null) apiKey = exchange.getRequestHeaders().getFirst("x-goog-api-key");
            if (apiKey == null) apiKey = exchange.getRequestHeaders().getFirst("api-key");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", responseType);
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private ForwardContext context(String body) {
        return ForwardContext.builder().apiUrl(base).apiKey("secret")
                .modelName("upstream-model").body(body).build();
    }

    @Test void anthropicConvertsSystemToolsResponseAndRejectsUnsupportedPartsBeforeNetwork() {
        reply = """
                {"content":[{"type":"text","text":"hello"},{"type":"tool_use","id":"t1","name":"lookup","input":{"x":1}}],
                 "stop_reason":"tool_use","usage":{"input_tokens":8,"output_tokens":3}}
                """;
        var adapter = new NativeChatAdapters.Anthropic();
        String body = """
                {"model":"alias","messages":[{"role":"system","content":"rules"},{"role":"user","content":"hi"}],
                 "tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object"}}}]}
                """;
        JSONObject result = JSON.parseObject(adapter.forward(context(body)));
        assertEquals("/messages", path);
        assertEquals("secret", apiKey);
        assertEquals("rules", JSON.parseObject(requestBody).getString("system"));
        assertEquals("upstream-model", JSON.parseObject(requestBody).getString("model"));
        assertEquals("tool_calls", result.getJSONArray("choices").getJSONObject(0).getString("finish_reason"));
        assertEquals(11, result.getJSONObject("usage").getIntValue("total_tokens"));
        assertThrows(ClientException.class, () -> adapter.forward(context(
                "{\"model\":\"alias\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\"}]}]}")));
        assertEquals(1, calls.get());
    }

    @Test void geminiConvertsContentAndUsage() {
        reply = """
                {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2}}
                """;
        var adapter = new NativeChatAdapters.Gemini();
        JSONObject result = JSON.parseObject(adapter.forward(context(
                "{\"model\":\"alias\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")));
        assertEquals("/models/upstream-model:generateContent", path);
        assertEquals("secret", apiKey);
        assertEquals("hi", JSON.parseObject(requestBody).getJSONArray("contents")
                .getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"));
        assertEquals("ok", result.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content"));
        assertEquals(6, result.getJSONObject("usage").getIntValue("total_tokens"));
    }

    @Test void ollamaOmitsAuthorizationAndMapsCounts() {
        reply = """
                {"message":{"role":"assistant","content":"local"},"done":true,
                 "prompt_eval_count":5,"eval_count":2}
                """;
        var adapter = new NativeChatAdapters.Ollama();
        JSONObject result = JSON.parseObject(adapter.forward(context(
                "{\"model\":\"alias\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")));
        assertEquals("/api/chat", path);
        assertNull(authorization);
        assertEquals("local", result.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content"));
        assertEquals(7, result.getJSONObject("usage").getIntValue("total_tokens"));
    }

    @Test void azureV1AndDeploymentUseExplicitRoutes() {
        reply = "{\"choices\":[]}";
        var v1 = new NativeChatAdapters.Azure(UpstreamProtocol.AZURE_OPENAI_V1);
        v1.forward(context("{\"model\":\"alias\",\"messages\":[]}"));
        assertEquals("/openai/v1/chat/completions", path);
        assertEquals("secret", apiKey);
        ForwardContext deployment = context("{\"model\":\"alias\",\"messages\":[]}");
        deployment.setProviderSettings("{\"deployment\":\"prod\",\"apiVersion\":\"2025-01-01\"}");
        new NativeChatAdapters.Azure(UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT).forward(deployment);
        assertEquals("/openai/deployments/prod/chat/completions?api-version=2025-01-01", path);
        assertEquals("prod", JSON.parseObject(requestBody).getString("model"));
    }

    @Test void customCompatibleEmbeddingsKeepOrderedVectors() {
        reply = """
                {"object":"list","data":[{"index":0,"embedding":[1,2]},{"index":1,"embedding":[3,4]}],
                 "usage":{"prompt_tokens":2,"total_tokens":2}}
                """;
        var adapter = new OpenAiJsonOperationAdapter(ModelOperation.EMBEDDINGS, "/embeddings");
        ForwardContext context = context("{\"model\":\"alias\",\"input\":[\"a\",\"b\"]}");
        context.setProvider("my-custom-supplier");
        JSONObject result = JSON.parseObject(adapter.forward(context));
        assertEquals("/embeddings", path);
        assertEquals("Bearer secret", authorization);
        assertEquals("upstream-model", JSON.parseObject(requestBody).getString("model"));
        assertEquals(2, result.getJSONArray("data").size());
    }

    @Test void embeddingsRejectUnsupportedInputBeforeNetworkAndRestoreUpstreamIndexOrder() {
        var adapter = new OpenAiJsonOperationAdapter(ModelOperation.EMBEDDINGS, "/embeddings");
        assertThrows(ClientException.class, () -> adapter.forward(context(
                "{\"model\":\"alias\",\"input\":\"hi\",\"dimensions\":0}")));
        assertThrows(ClientException.class, () -> adapter.forward(context(
                "{\"model\":\"alias\",\"input\":[\"hi\",42]}")));
        assertThrows(ClientException.class, () -> adapter.forward(context(
                "{\"model\":\"alias\",\"input\":\"hi\",\"encoding_format\":\"base64\"}")));
        assertEquals(0, calls.get());

        reply = "{\"data\":[{\"index\":1,\"embedding\":[3,4]},{\"index\":0,\"embedding\":[1,2]}]}";
        JSONObject result = JSON.parseObject(adapter.forward(context(
                "{\"model\":\"alias\",\"input\":[\"a\",\"b\"],\"dimensions\":2}")));
        assertEquals(0, result.getJSONArray("data").getJSONObject(0).getIntValue("index"));
        assertEquals(3, result.getJSONArray("data").getJSONObject(1).getJSONArray("embedding").getIntValue(0));
        reply = "{\"data\":[{\"index\":0,\"embedding\":[1]}]}";
        assertThrows(UpstreamFailureException.class, () -> adapter.forward(context(
                "{\"model\":\"alias\",\"input\":\"a\",\"dimensions\":2}")));
    }

    @Test void azureAndCompatibleUtilityRoutesPreserveModelAndAuthentication() {
        reply = "{\"object\":\"response\",\"id\":\"r1\"}";
        ForwardContext custom = context("{\"model\":\"alias\",\"input\":\"hi\"}");
        custom.setProvider("custom-provider");
        String response = new OpenAiJsonOperationAdapter(ModelOperation.RESPONSES, "/responses").forward(custom);
        assertEquals("response", JSON.parseObject(response).getString("object"));
        assertEquals("/responses", path);
        assertEquals("Bearer secret", authorization);
        assertEquals("upstream-model", JSON.parseObject(requestBody).getString("model"));

        new AzureJsonOperationAdapter(ModelOperation.RESPONSES, UpstreamProtocol.AZURE_OPENAI_V1).forward(custom);
        assertEquals("/openai/v1/responses", path);
        assertEquals("secret", apiKey);

        reply = "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}";
        ForwardContext deployment = context("{\"model\":\"alias\",\"input\":\"hi\",\"dimensions\":2}");
        deployment.setProviderSettings("{\"deployment\":\"vectors\",\"apiVersion\":\"2025-01-01\"}");
        new AzureJsonOperationAdapter(ModelOperation.EMBEDDINGS,
                UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT).forward(deployment);
        assertEquals("/openai/deployments/vectors/embeddings?api-version=2025-01-01", path);
        assertEquals("vectors", JSON.parseObject(requestBody).getString("model"));
    }

    @Test void compatibleAndAzureImagesUseExplicitRoutesAndRejectBadFormat() {
        reply = "{\"data\":[{\"url\":\"https://example.test/image.png\"}]}";
        var compatible = new OpenAiJsonOperationAdapter(ModelOperation.IMAGE_GENERATION, "/images/generations");
        compatible.forward(context("{\"model\":\"alias\",\"prompt\":\"cat\"}"));
        assertEquals("/images/generations", path);
        assertEquals("Bearer secret", authorization);
        int previousCalls = calls.get();
        assertThrows(ClientException.class, () -> compatible.forward(context(
                "{\"model\":\"alias\",\"prompt\":\"cat\",\"n\":11}")));
        assertEquals(previousCalls, calls.get());
        ForwardContext deployment = context("{\"model\":\"alias\",\"prompt\":\"cat\"}");
        deployment.setProviderSettings("{\"deployment\":\"images\",\"apiVersion\":\"2025-01-01\"}");
        new AzureJsonOperationAdapter(ModelOperation.IMAGE_GENERATION,
                UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT).forward(deployment);
        assertEquals("/openai/deployments/images/images/generations?api-version=2025-01-01", path);
        assertEquals("secret", apiKey);
    }

    @Test void nativeEmbeddingAdaptersPreserveBatchIndexes() {
        reply = "{\"embeddings\":[[1,2],[3,4]],\"prompt_eval_count\":3}";
        String body = "{\"model\":\"alias\",\"input\":[\"a\",\"b\"],\"dimensions\":2}";
        JSONObject ollama = JSON.parseObject(new NativeEmbeddingAdapters.Ollama().forward(context(body)));
        assertEquals(1, ollama.getJSONArray("data").getJSONObject(1).getIntValue("index"));
        assertEquals(3, ollama.getJSONObject("usage").getIntValue("prompt_tokens"));
        reply = "{\"embeddings\":[{\"values\":[1,2]},{\"values\":[3,4]}],\"usageMetadata\":{\"promptTokenCount\":4}}";
        JSONObject gemini = JSON.parseObject(new NativeEmbeddingAdapters.Gemini().forward(context(body)));
        assertTrue(path.endsWith(":batchEmbedContents"));
        assertEquals(2, gemini.getJSONArray("data").size());
        assertEquals(4, gemini.getJSONObject("usage").getIntValue("prompt_tokens"));
    }

    @Test void rerankSortsScoresWithOriginalDocumentIndexes() {
        reply = "{\"results\":[{\"index\":0,\"relevance_score\":0.2},{\"index\":1,\"relevance_score\":0.8}]}";
        String body = "{\"model\":\"alias\",\"query\":\"q\",\"documents\":[\"a\",\"b\"]}";
        JSONObject result = JSON.parseObject(new OpenRouterRerankAdapter().forward(context(body)));
        assertEquals("/rerank", path);
        assertEquals(1, result.getJSONArray("results").getJSONObject(0).getIntValue("index"));
        assertEquals("Bearer secret", authorization);
    }

    @Test void speechReturnsBinaryAndRejectsUnsupportedFormatBeforeNetwork() {
        reply = "abc";
        var adapter = new OpenAiMediaAdapter(ModelOperation.SPEECH, UpstreamProtocol.OPENAI_COMPAT);
        JSONObject input = JSON.parseObject("{\"model\":\"alias\",\"input\":\"hi\",\"voice\":\"alloy\",\"response_format\":\"mp3\"}");
        var result = (AdapterExchange.BinaryResult) adapter.invoke(context(null),
                new AdapterExchange.JsonRequest(ModelOperation.SPEECH, input));
        assertEquals(MediaType.parseMediaType("audio/mpeg"), result.contentType());
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), result.body());
        assertEquals("/audio/speech", path);
        input.put("response_format", "bad");
        assertThrows(ClientException.class, () -> adapter.invoke(context(null),
                new AdapterExchange.JsonRequest(ModelOperation.SPEECH, input)));
        assertEquals(1, calls.get());
    }

    @Test void anthropicStreamProducesContentUsageAndOneDone() {
        responseType = "text/event-stream";
        reply = """
                event: message_start
                data: {"message":{"usage":{"input_tokens":2}}}

                event: content_block_delta
                data: {"delta":{"type":"text_delta","text":"hi"}}

                event: message_delta
                data: {"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

                event: message_stop
                data: {}

                """;
        var events = new NativeChatAdapters.Anthropic().stream(context(
                "{\"model\":\"alias\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .collectList().block();
        assertNotNull(events);
        assertEquals(1, events.stream().filter(event -> "[DONE]".equals(event.data())).count());
        assertTrue(events.stream().anyMatch(event -> event.data() != null && event.data().contains("\"content\":\"hi\"")));
        assertTrue(events.stream().anyMatch(event -> event.data() != null && event.data().contains("total_tokens")));
    }

    @Test void responsesStreamKeepsNamedEventsInOrder() {
        responseType = "text/event-stream";
        reply = """
                event: response.created
                data: {"type":"response.created"}

                event: response.completed
                data: {"type":"response.completed","response":{"usage":{"input_tokens":2,"output_tokens":1,"total_tokens":3}}}

                """;
        var events = new OpenAiJsonOperationAdapter(ModelOperation.RESPONSES, "/responses")
                .stream(context("{\"model\":\"alias\",\"input\":\"hello\",\"stream\":true}"))
                .collectList().block();
        assertNotNull(events);
        assertEquals("response.created", events.get(0).event());
        assertEquals("response.completed", events.get(1).event());
        assertEquals("/responses", path);
    }

    @Test void transcriptionMultipartPreservesFileAndDurationMetadata() {
        reply = "{\"text\":\"hello\",\"duration\":1.5}";
        var adapter = new OpenAiMediaAdapter(ModelOperation.TRANSCRIPTION, UpstreamProtocol.OPENAI_COMPAT);
        var request = new AdapterExchange.MultipartRequest(ModelOperation.TRANSCRIPTION,
                Map.of("response_format", "verbose_json"), "clip.wav", MediaType.parseMediaType("audio/wav"),
                "audio-data".getBytes(StandardCharsets.UTF_8));
        var result = (AdapterExchange.BinaryResult) adapter.invoke(context(null), request);
        assertEquals("/audio/transcriptions", path);
        assertTrue(requestBody.contains("filename=\"clip.wav\""));
        assertTrue(requestBody.contains("upstream-model"));
        assertEquals(1500L, result.usage().audioDurationMs());
        assertEquals(MediaType.APPLICATION_JSON, result.contentType());
    }

    @Test void geminiImageRequiresBase64AndMapsProducedImage() {
        reply = "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"QUJD\"}}]}}]}";
        var adapter = new GeminiImageAdapter();
        JSONObject result = JSON.parseObject(adapter.forward(context(
                "{\"model\":\"alias\",\"prompt\":\"cat\",\"response_format\":\"b64_json\"}")));
        assertEquals("QUJD", result.getJSONArray("data").getJSONObject(0).getString("b64_json"));
        assertEquals("/models/upstream-model:generateContent", path);
        assertThrows(ClientException.class, () -> adapter.forward(context(
                "{\"model\":\"alias\",\"prompt\":\"cat\",\"response_format\":\"url\"}")));
        assertEquals(1, calls.get());
    }

    @Test void ollamaStreamMapsTerminalUsageAndOneDone() {
        responseType = "application/x-ndjson";
        reply = """
                {"message":{"content":"hi"},"done":false}
                {"message":{"content":"!"},"done":true,"prompt_eval_count":2,"eval_count":1}
                """;
        var events = new NativeChatAdapters.Ollama().stream(context(
                "{\"model\":\"alias\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .collectList().block();
        assertNotNull(events);
        assertEquals(1, events.stream().filter(event -> "[DONE]".equals(event.data())).count());
        assertTrue(events.stream().anyMatch(event -> event.data() != null && event.data().contains("total_tokens")));
    }

    @Test void geminiStreamMapsContentAndOneDone() {
        responseType = "text/event-stream";
        reply = """
                data: {"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}

                data: {"candidates":[{"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":1}}

                """;
        var events = new NativeChatAdapters.Gemini().stream(context(
                "{\"model\":\"alias\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .collectList().block();
        assertNotNull(events);
        assertEquals(1, events.stream().filter(event -> "[DONE]".equals(event.data())).count());
        assertTrue(events.stream().anyMatch(event -> event.data() != null && event.data().contains("total_tokens")));
    }

    @Test void oversizedAudioAndImageOutputStopAtConfiguredBound() {
        reply = "0123456789";
        var speech = new OpenAiMediaAdapter(ModelOperation.SPEECH, UpstreamProtocol.OPENAI_COMPAT);
        ReflectionTestUtils.setField(speech, "maxOutputBytes", 4);
        JSONObject input = JSON.parseObject("{\"model\":\"alias\",\"input\":\"hi\",\"voice\":\"alloy\"}");
        assertThrows(ClientException.class, () -> speech.invoke(context(null),
                new AdapterExchange.JsonRequest(ModelOperation.SPEECH, input)));
        var images = new OpenAiJsonOperationAdapter(ModelOperation.IMAGE_GENERATION, "/images/generations");
        ReflectionTestUtils.setField(images, "maxImageJsonBytes", 4);
        assertThrows(ClientException.class, () -> images.forward(context(
                "{\"model\":\"alias\",\"prompt\":\"cat\"}")));
    }

    @Test void sharedChatAdapterWorksForOpenAiOpenRouterAndCustomNames() {
        reply = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        var adapter = new OpenAiCompatibleAdapter(5000, 120000);
        for (String provider : new String[] {"openai", "openrouter", "some-new-provider"}) {
            ForwardContext context = context("{\"model\":\"alias\",\"messages\":[]}");
            context.setProvider(provider);
            assertEquals("ok", JSON.parseObject(adapter.forward(context)).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content"));
            assertEquals("/chat/completions", path);
            assertEquals("Bearer secret", authorization);
            assertEquals("upstream-model", JSON.parseObject(requestBody).getString("model"));
        }
        assertEquals(3, calls.get());
    }

    @Test void nativeErrorDistinguishesValidationAndTemporaryFailure() {
        responseStatus = 400;
        reply = "{\"error\":{\"message\":\"invalid input\"}}";
        var adapter = new NativeChatAdapters.Anthropic();
        String body = "{\"model\":\"alias\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        UpstreamFailureException bad = assertThrows(UpstreamFailureException.class,
                () -> adapter.forward(context(body)));
        assertFalse(bad.isRetryable());
        assertEquals("invalid input", bad.getMessage());
        responseStatus = 503;
        UpstreamFailureException unavailable = assertThrows(UpstreamFailureException.class,
                () -> adapter.forward(context(body)));
        assertTrue(unavailable.isRetryable());
    }

    @Test void azureUtilityAndAudioRoutesUseConfiguredVersionAndApiKey() {
        reply = "{\"object\":\"response\"}";
        var responses = new AzureJsonOperationAdapter(ModelOperation.RESPONSES, UpstreamProtocol.AZURE_OPENAI_V1);
        responses.forward(context("{\"model\":\"alias\",\"input\":\"hi\"}"));
        assertEquals("/openai/v1/responses", path);
        assertEquals("secret", apiKey);
        ForwardContext deployment = context("{\"model\":\"alias\",\"input\":\"hi\"}");
        deployment.setProviderSettings("{\"deployment\":\"vectors\",\"apiVersion\":\"2025-01-01\"}");
        reply = "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}";
        new AzureJsonOperationAdapter(ModelOperation.EMBEDDINGS, UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT)
                .forward(deployment);
        assertEquals("/openai/deployments/vectors/embeddings?api-version=2025-01-01", path);
        assertEquals("vectors", JSON.parseObject(requestBody).getString("model"));
        reply = "audio";
        var adapter = new OpenAiMediaAdapter(ModelOperation.SPEECH, UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT);
        JSONObject input = JSON.parseObject("{\"model\":\"alias\",\"input\":\"hi\",\"voice\":\"alloy\"}");
        adapter.invoke(deployment, new AdapterExchange.JsonRequest(ModelOperation.SPEECH, input));
        assertEquals("/openai/deployments/vectors/audio/speech?api-version=2025-01-01", path);
        assertEquals("secret", apiKey);
    }

    @Test void azureAudioAndImageV1AndDeploymentUseSelectedRoute() {
        reply = "{\"text\":\"hello\"}";
        var request = new AdapterExchange.MultipartRequest(ModelOperation.TRANSCRIPTION,
                Map.of(), "clip.wav", MediaType.parseMediaType("audio/wav"),
                "audio-data".getBytes(StandardCharsets.UTF_8));
        new OpenAiMediaAdapter(ModelOperation.TRANSCRIPTION,
                UpstreamProtocol.AZURE_OPENAI_V1).invoke(context(null), request);
        assertEquals("/openai/v1/audio/transcriptions", path);
        assertEquals("secret", apiKey);

        ForwardContext deployment = context(null);
        deployment.setProviderSettings("{\"deployment\":\"speech\",\"apiVersion\":\"2025-01-01\"}");
        new OpenAiMediaAdapter(ModelOperation.TRANSCRIPTION,
                UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT).invoke(deployment, request);
        assertEquals("/openai/deployments/speech/audio/transcriptions?api-version=2025-01-01", path);
        assertTrue(requestBody.contains("name=\"model\""));
        assertTrue(requestBody.contains("speech"));

        reply = "{\"data\":[{\"url\":\"https://example.test/image.png\"}]}";
        new AzureJsonOperationAdapter(ModelOperation.IMAGE_GENERATION,
                UpstreamProtocol.AZURE_OPENAI_V1).forward(context(
                "{\"model\":\"alias\",\"prompt\":\"cat\"}"));
        assertEquals("/openai/v1/images/generations", path);
        assertEquals("upstream-model", JSON.parseObject(requestBody).getString("model"));
    }

    @Test void nativeStreamCancellationAndMidstreamFailureNeverAppendDone() {
        responseType = "text/event-stream";
        reply = """
                event: message_start
                data: {"message":{"usage":{"input_tokens":2}}}

                event: content_block_delta
                data: {"delta":{"type":"text_delta","text":"hi"}}

                """;
        var adapter = new NativeChatAdapters.Anthropic();
        String body = "{\"model\":\"alias\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        var cancelled = adapter.stream(context(body)).take(1).collectList().block();
        assertNotNull(cancelled);
        assertEquals(1, cancelled.size());
        assertNotEquals("[DONE]", cancelled.getFirst().data());

        reply = """
                event: message_start
                data: {"message":{"usage":{"input_tokens":2}}}

                event: content_block_delta
                data: malformed-json

                """;
        var emitted = new ArrayList<org.springframework.http.codec.ServerSentEvent<String>>();
        assertThrows(RuntimeException.class, () -> adapter.stream(context(body))
                .doOnNext(emitted::add).blockLast());
        assertEquals(1, emitted.size());
        assertTrue(emitted.stream().noneMatch(event -> "[DONE]".equals(event.data())));
    }

    @Test void responsesCancellationPreservesFirstNamedEventWithoutExtraFrames() {
        responseType = "text/event-stream";
        reply = """
                event: response.created
                data: {"type":"response.created"}

                event: response.completed
                data: {"type":"response.completed"}

                """;
        var events = new OpenAiJsonOperationAdapter(ModelOperation.RESPONSES, "/responses")
                .stream(context("{\"model\":\"alias\",\"stream\":true}"))
                .take(1).collectList().block();
        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals("response.created", events.getFirst().event());
    }
}
