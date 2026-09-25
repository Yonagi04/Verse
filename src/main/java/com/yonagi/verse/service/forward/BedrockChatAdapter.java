package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;

/** AWS 凭据链签名的 Bedrock Converse Chat 适配器。 */
@Component
public class BedrockChatAdapter implements ProviderAdapter, AdapterRegistration {
    private final Map<String, BedrockRuntimeClient> clients = new ConcurrentHashMap<>();
    private final Map<String, BedrockRuntimeAsyncClient> streamClients = new ConcurrentHashMap<>();

    @Override public ModelOperation operation() { return ModelOperation.CHAT_COMPLETIONS; }
    @Override public UpstreamProtocol protocol() { return UpstreamProtocol.BEDROCK_CONVERSE; }
    @Override public String provider() { return "bedrock"; }

    @Override
    public String forward(ForwardContext context) {
        JSONObject input;
        try { input = JSON.parseObject(context.getBody()); }
        catch (RuntimeException e) { throw unsupported(); }
        if (input == null) throw unsupported();
        JSONObject settings = JSON.parseObject(context.getProviderSettings());
        String region = settings == null ? null : settings.getString("region");
        if (region == null || region.isBlank()) throw unsupported();
        ConverseRequest request = request(context, input);
        try {
            BedrockRuntimeClient client = clients.computeIfAbsent(region, name ->
                    BedrockRuntimeClient.builder().region(Region.of(name))
                            .credentialsProvider(DefaultCredentialsProvider.create()).build());
            return JSON.toJSONString(response(context, client.converse(request)));
        } catch (BedrockRuntimeException e) {
            throw UpstreamErrors.from(e.statusCode(), null);
        } catch (SdkClientException e) {
            throw UpstreamErrors.timeout();
        }
    }

    @Override public Flux<ServerSentEvent<String>> stream(ForwardContext context) {
        JSONObject input;
        try { input = JSON.parseObject(context.getBody()); }
        catch (RuntimeException e) { return Flux.error(unsupported()); }
        if (input == null) return Flux.error(unsupported());
        JSONObject settings = JSON.parseObject(context.getProviderSettings());
        String region = settings == null ? null : settings.getString("region");
        if (region == null || region.isBlank()) return Flux.error(unsupported());
        ConverseRequest validated = request(context, input);
        ConverseStreamRequest.Builder builder = ConverseStreamRequest.builder()
                .modelId(validated.modelId()).messages(validated.messages())
                .inferenceConfig(validated.inferenceConfig());
        if (!validated.system().isEmpty()) builder.system(validated.system());
        if (validated.toolConfig() != null) builder.toolConfig(validated.toolConfig());
        ConverseStreamRequest streamRequest = builder.build();
        return Flux.create(sink -> {
            AtomicBoolean ended = new AtomicBoolean();
            BedrockRuntimeAsyncClient client = streamClients.computeIfAbsent(region, name ->
                    BedrockRuntimeAsyncClient.builder().region(Region.of(name))
                            .credentialsProvider(DefaultCredentialsProvider.create()).build());
            ConverseStreamResponseHandler.Visitor visitor = ConverseStreamResponseHandler.Visitor.builder()
                    .onMessageStart(event -> {
                        JSONObject delta = new JSONObject();
                        delta.put("role", "assistant");
                        sink.next(chunk(context, delta, null, null));
                    })
                    .onContentBlockStart(event -> {
                        if (event.start() == null || event.start().toolUse() == null) return;
                        var tool = event.start().toolUse();
                        JSONObject function = new JSONObject();
                        function.put("name", tool.name());
                        function.put("arguments", "");
                        JSONObject call = new JSONObject();
                        call.put("index", event.contentBlockIndex());
                        call.put("id", tool.toolUseId());
                        call.put("type", "function");
                        call.put("function", function);
                        JSONArray calls = new JSONArray();
                        calls.add(call);
                        JSONObject delta = new JSONObject();
                        delta.put("tool_calls", calls);
                        sink.next(chunk(context, delta, null, null));
                    })
                    .onContentBlockDelta(event -> {
                        if (event.delta() == null) return;
                        JSONObject delta = new JSONObject();
                        if (event.delta().text() != null) delta.put("content", event.delta().text());
                        else if (event.delta().toolUse() != null) {
                            JSONObject function = new JSONObject();
                            function.put("arguments", event.delta().toolUse().input());
                            JSONObject call = new JSONObject();
                            call.put("index", event.contentBlockIndex());
                            call.put("function", function);
                            JSONArray calls = new JSONArray();
                            calls.add(call);
                            delta.put("tool_calls", calls);
                        } else return;
                        sink.next(chunk(context, delta, null, null));
                    })
                    .onMessageStop(event -> sink.next(chunk(context, new JSONObject(),
                            "tool_use".equals(event.stopReasonAsString()) ? "tool_calls"
                                    : "max_tokens".equals(event.stopReasonAsString()) ? "length" : "stop", null)))
                    .onMetadata(event -> {
                        if (event.usage() == null) return;
                        TokenUsage usage = event.usage();
                        JSONObject counts = new JSONObject();
                        counts.put("prompt_tokens", usage.inputTokens());
                        counts.put("completion_tokens", usage.outputTokens());
                        counts.put("total_tokens", usage.totalTokens());
                        sink.next(chunk(context, null, null, counts));
                    }).build();
            var future = client.converseStream(streamRequest,
                    ConverseStreamResponseHandler.builder().subscriber(visitor).build());
            future.whenComplete((ignored, error) -> {
                if (!ended.compareAndSet(false, true)) return;
                if (error != null) sink.error(error);
                else {
                    sink.next(ServerSentEvent.builder("[DONE]").build());
                    sink.complete();
                }
            });
            sink.onCancel(() -> {
                ended.set(true);
                future.cancel(true);
            });
        });
    }

    private ServerSentEvent<String> chunk(ForwardContext context, JSONObject delta,
                                           String reason, JSONObject usage) {
        JSONObject envelope = new JSONObject();
        envelope.put("object", "chat.completion.chunk");
        envelope.put("model", context.getModelName());
        JSONArray choices = new JSONArray();
        if (delta != null || reason != null) {
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("delta", delta == null ? new JSONObject() : delta);
            choice.put("finish_reason", reason);
            choices.add(choice);
        }
        envelope.put("choices", choices);
        if (usage != null) envelope.put("usage", usage);
        return ServerSentEvent.builder(JSON.toJSONString(envelope)).build();
    }

    @PreDestroy
    public void close() {
        clients.values().forEach(BedrockRuntimeClient::close);
        streamClients.values().forEach(BedrockRuntimeAsyncClient::close);
    }

    ConverseRequest request(ForwardContext context, JSONObject input) {
        if (!Set.of("model", "messages", "temperature", "top_p", "max_tokens", "stop", "tools", "tool_choice", "stream")
                .containsAll(input.keySet())) throw unsupported();
        JSONArray source = input.getJSONArray("messages");
        if (source == null || source.isEmpty()) throw unsupported();
        List<Message> messages = new ArrayList<>();
        List<SystemContentBlock> systems = new ArrayList<>();
        for (Object item : source) {
            if (!(item instanceof JSONObject message)) throw unsupported();
            String role = message.getString("role");
            if ("system".equals(role) || "developer".equals(role)) {
                if (!(message.get("content") instanceof String text)) throw unsupported();
                systems.add(SystemContentBlock.fromText(text));
            } else if ("user".equals(role) || "assistant".equals(role)) {
                if (!Set.of("role", "content", "tool_calls").containsAll(message.keySet())) throw unsupported();
                List<ContentBlock> blocks = new ArrayList<>();
                if (message.get("content") instanceof String text) blocks.add(ContentBlock.fromText(text));
                else if (message.get("content") != null) throw unsupported();
                JSONArray calls = message.getJSONArray("tool_calls");
                if (calls != null) for (Object callItem : calls) {
                    if (!(callItem instanceof JSONObject call) || !"function".equals(call.getString("type"))) throw unsupported();
                    JSONObject function = call.getJSONObject("function");
                    if (function == null) throw unsupported();
                    Object args;
                    try { args = JSON.parse(function.getString("arguments")); }
                    catch (RuntimeException e) { throw unsupported(); }
                    blocks.add(ContentBlock.fromToolUse(builder -> builder
                            .toolUseId(call.getString("id")).name(function.getString("name"))
                            .input(document(args))));
                }
                if (blocks.isEmpty()) throw unsupported();
                messages.add(Message.builder().role("assistant".equals(role)
                        ? ConversationRole.ASSISTANT : ConversationRole.USER).content(blocks).build());
            } else if ("tool".equals(role)) {
                if (!Set.of("role", "tool_call_id", "content").containsAll(message.keySet())
                        || !(message.get("content") instanceof String text)) throw unsupported();
                messages.add(Message.builder().role(ConversationRole.USER)
                        .content(ContentBlock.fromToolResult(result -> result
                                .toolUseId(message.getString("tool_call_id"))
                                .content(ToolResultContentBlock.fromText(text)))).build());
            } else throw unsupported();
        }
        ConverseRequest.Builder request = ConverseRequest.builder()
                .modelId(context.getModelName()).messages(messages);
        if (!systems.isEmpty()) request.system(systems);
        InferenceConfiguration.Builder inference = InferenceConfiguration.builder();
        if (input.getInteger("max_tokens") != null) inference.maxTokens(input.getInteger("max_tokens"));
        if (input.getFloat("temperature") != null) inference.temperature(input.getFloat("temperature"));
        if (input.getFloat("top_p") != null) inference.topP(input.getFloat("top_p"));
        Object stop = input.get("stop");
        if (stop instanceof String value) inference.stopSequences(value);
        else if (stop instanceof JSONArray array) inference.stopSequences(array.toJavaList(String.class));
        else if (stop != null) throw unsupported();
        request.inferenceConfig(inference.build());
        JSONArray tools = input.getJSONArray("tools");
        if (tools != null && !tools.isEmpty()) {
            List<Tool> mapped = new ArrayList<>();
            for (Object item : tools) {
                if (!(item instanceof JSONObject tool) || !"function".equals(tool.getString("type"))) throw unsupported();
                JSONObject function = tool.getJSONObject("function");
                if (function == null || function.getJSONObject("parameters") == null) throw unsupported();
                ToolSpecification.Builder spec = ToolSpecification.builder().name(function.getString("name"))
                        .inputSchema(ToolInputSchema.fromJson(document(function.getJSONObject("parameters"))));
                if (function.getString("description") != null) spec.description(function.getString("description"));
                mapped.add(Tool.fromToolSpec(spec.build()));
            }
            request.toolConfig(ToolConfiguration.builder().tools(mapped).build());
        }
        if (input.containsKey("tool_choice") && !"auto".equals(input.getString("tool_choice"))) throw unsupported();
        return request.build();
    }

    JSONObject response(ForwardContext context, ConverseResponse upstream) {
        if (upstream.output() == null || upstream.output().message() == null) throw UpstreamErrors.from(502, null);
        StringBuilder text = new StringBuilder();
        JSONArray calls = new JSONArray();
        for (ContentBlock block : upstream.output().message().content()) {
            if (block.text() != null) text.append(block.text());
            if (block.toolUse() != null) {
                JSONObject function = new JSONObject();
                function.put("name", block.toolUse().name());
                function.put("arguments", JSON.toJSONString(block.toolUse().input().unwrap()));
                JSONObject call = new JSONObject();
                call.put("id", block.toolUse().toolUseId());
                call.put("type", "function");
                call.put("function", function);
                calls.add(call);
            }
        }
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", text.toString());
        if (!calls.isEmpty()) message.put("tool_calls", calls);
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", message);
        String reason = upstream.stopReasonAsString();
        choice.put("finish_reason", "tool_use".equals(reason) ? "tool_calls"
                : "max_tokens".equals(reason) ? "length" : "stop");
        JSONArray choices = new JSONArray();
        choices.add(choice);
        JSONObject result = new JSONObject();
        result.put("object", "chat.completion");
        result.put("model", context.getModelName());
        result.put("choices", choices);
        TokenUsage usage = upstream.usage();
        if (usage != null) {
            JSONObject counts = new JSONObject();
            counts.put("prompt_tokens", usage.inputTokens());
            counts.put("completion_tokens", usage.outputTokens());
            counts.put("total_tokens", usage.totalTokens());
            result.put("usage", counts);
        }
        return result;
    }

    private Document document(Object value) {
        if (value == null) return Document.fromNull();
        if (value instanceof String text) return Document.fromString(text);
        if (value instanceof Boolean bool) return Document.fromBoolean(bool);
        if (value instanceof Number number) return Document.fromNumber(number.toString());
        if (value instanceof Map<?, ?> map) {
            Map<String, Document> converted = new LinkedHashMap<>();
            map.forEach((key, item) -> converted.put(String.valueOf(key), document(item)));
            return Document.fromMap(converted);
        }
        if (value instanceof List<?> list) return Document.fromList(list.stream().map(this::document).toList());
        throw unsupported();
    }

    private ClientException unsupported() {
        return new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
    }
}
