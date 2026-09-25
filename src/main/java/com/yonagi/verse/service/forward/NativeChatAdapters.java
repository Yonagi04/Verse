package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 原生 Chat 协议的 JSON 编解码；只接受能无损映射的客户端字段。 */
public final class NativeChatAdapters {
    private NativeChatAdapters() {}

    private abstract static class Base implements ProviderAdapter, AdapterRegistration {
        private final RestClient client;
        final WebClient webClient = WebClient.create();

        Base() {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5000);
            factory.setReadTimeout(120000);
            client = RestClient.builder().requestFactory(factory).build();
        }

        @Override public ModelOperation operation() { return ModelOperation.CHAT_COMPLETIONS; }
        abstract String url(ForwardContext context);
        abstract JSONObject request(ForwardContext context, JSONObject input);
        abstract JSONObject response(ForwardContext context, JSONObject upstream);
        abstract RestClient.RequestBodySpec authenticate(RestClient.RequestBodySpec request, ForwardContext context);

        @Override
        public String forward(ForwardContext context) {
            JSONObject input;
            try { input = JSON.parseObject(context.getBody()); }
            catch (RuntimeException e) { throw unsupported(); }
            if (input == null || input.getJSONArray("messages") == null) throw unsupported();
            JSONObject outbound = request(context, input);
            try {
                String raw = authenticate(client.post().uri(url(context)).contentType(MediaType.APPLICATION_JSON), context)
                        .body(JSON.toJSONString(outbound)).retrieve().body(String.class);
                JSONObject parsed = JSON.parseObject(raw);
                if (parsed == null) throw UpstreamErrors.from(502, null);
                return JSON.toJSONString(response(context, parsed));
            } catch (RestClientResponseException e) {
                throw UpstreamErrors.from(e.getStatusCode().value(), e.getResponseBodyAsString());
            } catch (ResourceAccessException e) {
                throw UpstreamErrors.timeout();
            }
        }

        @Override public Flux<ServerSentEvent<String>> stream(ForwardContext context) {
            return Flux.error(unsupported());
        }

        static void fields(JSONObject input, String... allowed) {
            Set<String> names = Set.of(allowed);
            for (String key : input.keySet()) if (!names.contains(key)) throw unsupported();
        }

        static String text(JSONObject message) {
            Object content = message.get("content");
            if (content instanceof String value) return value;
            throw unsupported();
        }

        static String base(String url) {
            if (url == null || url.isBlank()) throw unsupported();
            return url.replaceAll("/+$", "");
        }

        static JSONObject openAi(ForwardContext context, String content, JSONArray toolCalls,
                                 String finishReason, JSONObject usage) {
            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", content);
            if (toolCalls != null && !toolCalls.isEmpty()) message.put("tool_calls", toolCalls);
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", finishReason);
            JSONObject result = new JSONObject();
            result.put("object", "chat.completion");
            result.put("model", context.getModelName());
            JSONArray choices = new JSONArray();
            choices.add(choice);
            result.put("choices", choices);
            result.put("usage", usage);
            return result;
        }

        static JSONObject usage(Long input, Long output) {
            if (input == null || output == null) return null;
            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", input);
            usage.put("completion_tokens", output);
            usage.put("total_tokens", input + output);
            return usage;
        }

        static ServerSentEvent<String> chunk(ForwardContext context, JSONObject delta,
                                             String finishReason, JSONObject usage) {
            JSONObject envelope = new JSONObject();
            envelope.put("object", "chat.completion.chunk");
            envelope.put("model", context.getModelName());
            if (delta != null || finishReason != null) {
                JSONObject choice = new JSONObject();
                choice.put("index", 0);
                choice.put("delta", delta == null ? new JSONObject() : delta);
                choice.put("finish_reason", finishReason);
                JSONArray choices = new JSONArray();
                choices.add(choice);
                envelope.put("choices", choices);
            } else envelope.put("choices", new JSONArray());
            if (usage != null) envelope.put("usage", usage);
            return ServerSentEvent.builder(JSON.toJSONString(envelope)).build();
        }

        static ServerSentEvent<String> done() { return ServerSentEvent.builder("[DONE]").build(); }

        static ClientException unsupported() {
            return new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
        }
    }

    public static final class Anthropic extends Base {
        @Override public UpstreamProtocol protocol() { return UpstreamProtocol.ANTHROPIC_MESSAGES; }
        @Override public String provider() { return "anthropic"; }
        @Override String url(ForwardContext context) { return base(context.getApiUrl()) + "/messages"; }
        @Override RestClient.RequestBodySpec authenticate(RestClient.RequestBodySpec request, ForwardContext context) {
            return request.header("x-api-key", context.getApiKey()).header("anthropic-version", "2023-06-01");
        }

        @Override JSONObject request(ForwardContext context, JSONObject input) {
            fields(input, "model", "messages", "temperature", "top_p", "max_tokens", "stream", "tools", "tool_choice", "stop");
            JSONArray messages = new JSONArray();
            StringBuilder system = new StringBuilder();
            for (Object item : input.getJSONArray("messages")) {
                if (!(item instanceof JSONObject message)) throw unsupported();
                String role = message.getString("role");
                if ("system".equals(role) || "developer".equals(role)) {
                    fields(message, "role", "content");
                    if (!system.isEmpty()) system.append("\n");
                    system.append(text(message));
                } else if ("user".equals(role) || "assistant".equals(role)) {
                    fields(message, "role", "content", "tool_calls");
                    JSONObject mapped = new JSONObject();
                    mapped.put("role", role);
                    JSONArray calls = message.getJSONArray("tool_calls");
                    if (calls == null) mapped.put("content", text(message));
                    else {
                        if (!"assistant".equals(role)) throw unsupported();
                        JSONArray blocks = new JSONArray();
                        if (message.get("content") instanceof String content && !content.isEmpty()) {
                            JSONObject block = new JSONObject();
                            block.put("type", "text");
                            block.put("text", content);
                            blocks.add(block);
                        } else if (message.get("content") != null) throw unsupported();
                        for (Object callItem : calls) {
                            if (!(callItem instanceof JSONObject call) || !"function".equals(call.getString("type"))) throw unsupported();
                            JSONObject function = call.getJSONObject("function");
                            if (function == null) throw unsupported();
                            Object args;
                            try { args = JSON.parse(function.getString("arguments")); }
                            catch (RuntimeException e) { throw unsupported(); }
                            JSONObject block = new JSONObject();
                            block.put("type", "tool_use");
                            block.put("id", call.getString("id"));
                            block.put("name", function.getString("name"));
                            block.put("input", args);
                            blocks.add(block);
                        }
                        mapped.put("content", blocks);
                    }
                    messages.add(mapped);
                } else if ("tool".equals(role)) {
                    fields(message, "role", "tool_call_id", "content");
                    JSONObject block = new JSONObject();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", message.getString("tool_call_id"));
                    block.put("content", text(message));
                    JSONArray blocks = new JSONArray();
                    blocks.add(block);
                    JSONObject mapped = new JSONObject();
                    mapped.put("role", "user");
                    mapped.put("content", blocks);
                    messages.add(mapped);
                } else throw unsupported();
            }
            JSONObject request = new JSONObject();
            request.put("model", context.getModelName());
            request.put("messages", messages);
            request.put("max_tokens", input.getInteger("max_tokens") == null ? 1024 : input.getInteger("max_tokens"));
            if (!system.isEmpty()) request.put("system", system.toString());
            for (String key : Set.of("temperature", "top_p")) if (input.containsKey(key)) request.put(key, input.get(key));
            if (input.containsKey("stop")) request.put("stop_sequences", input.get("stop"));
            JSONArray tools = input.getJSONArray("tools");
            if (tools != null) {
                JSONArray mappedTools = new JSONArray();
                for (Object item : tools) {
                    if (!(item instanceof JSONObject tool) || !"function".equals(tool.getString("type"))) throw unsupported();
                    JSONObject function = tool.getJSONObject("function");
                    if (function == null) throw unsupported();
                    JSONObject mapped = new JSONObject();
                    mapped.put("name", function.getString("name"));
                    if (function.containsKey("description")) mapped.put("description", function.get("description"));
                    mapped.put("input_schema", function.get("parameters"));
                    mappedTools.add(mapped);
                }
                request.put("tools", mappedTools);
            }
            if (input.containsKey("tool_choice")) {
                Object choice = input.get("tool_choice");
                if (choice instanceof String value && Set.of("auto", "none", "required").contains(value)) {
                    JSONObject mapped = new JSONObject();
                    mapped.put("type", "required".equals(value) ? "any" : value);
                    request.put("tool_choice", mapped);
                } else throw unsupported();
            }
            return request;
        }

        @Override JSONObject response(ForwardContext context, JSONObject upstream) {
            StringBuilder content = new StringBuilder();
            JSONArray calls = new JSONArray();
            JSONArray blocks = upstream.getJSONArray("content");
            if (blocks != null) for (Object item : blocks) {
                if (!(item instanceof JSONObject block)) continue;
                if ("text".equals(block.getString("type"))) content.append(block.getString("text"));
                if ("tool_use".equals(block.getString("type"))) {
                    JSONObject function = new JSONObject();
                    function.put("name", block.getString("name"));
                    function.put("arguments", JSON.toJSONString(block.get("input")));
                    JSONObject call = new JSONObject();
                    call.put("id", block.getString("id"));
                    call.put("type", "function");
                    call.put("function", function);
                    calls.add(call);
                }
            }
            JSONObject raw = upstream.getJSONObject("usage");
            String reason = upstream.getString("stop_reason");
            return openAi(context, content.toString(), calls,
                    "tool_use".equals(reason) ? "tool_calls" : "max_tokens".equals(reason) ? "length" : "stop",
                    raw == null ? null : usage(raw.getLong("input_tokens"), raw.getLong("output_tokens")));
        }

        @Override public Flux<ServerSentEvent<String>> stream(ForwardContext context) {
            JSONObject input = JSON.parseObject(context.getBody());
            if (input == null) return Flux.error(unsupported());
            JSONObject outbound = request(context, input);
            outbound.put("stream", true);
            return Flux.defer(() -> {
                JSONObject state = new JSONObject();
                state.put("input", null);
                state.put("output", null);
                state.put("toolIndex", 0);
                return webClient.post().uri(url(context))
                        .header("x-api-key", context.getApiKey()).header("anthropic-version", "2023-06-01")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .bodyValue(JSON.toJSONString(outbound)).retrieve()
                        .bodyToFlux(new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<String>>() {})
                        .handle((event, sink) -> {
                            try {
                                JSONObject payload = JSON.parseObject(event.data());
                                if (payload == null) return;
                                String type = event.event() == null ? payload.getString("type") : event.event();
                                if ("message_start".equals(type)) {
                                    JSONObject message = payload.getJSONObject("message");
                                    JSONObject usage = message == null ? null : message.getJSONObject("usage");
                                    if (usage != null) state.put("input", usage.getLong("input_tokens"));
                                    JSONObject delta = new JSONObject();
                                    delta.put("role", "assistant");
                                    sink.next(chunk(context, delta, null, null));
                                } else if ("content_block_start".equals(type)) {
                                    JSONObject block = payload.getJSONObject("content_block");
                                    if (block != null && "tool_use".equals(block.getString("type"))) {
                                        int index = state.getIntValue("toolIndex");
                                        state.put("toolIndex", index + 1);
                                        JSONObject function = new JSONObject();
                                        function.put("name", block.getString("name"));
                                        function.put("arguments", "");
                                        JSONObject call = new JSONObject();
                                        call.put("index", index);
                                        call.put("id", block.getString("id"));
                                        call.put("type", "function");
                                        call.put("function", function);
                                        JSONArray calls = new JSONArray();
                                        calls.add(call);
                                        JSONObject delta = new JSONObject();
                                        delta.put("tool_calls", calls);
                                        sink.next(chunk(context, delta, null, null));
                                    }
                                } else if ("content_block_delta".equals(type)) {
                                    JSONObject part = payload.getJSONObject("delta");
                                    if (part == null) return;
                                    JSONObject delta = new JSONObject();
                                    if ("text_delta".equals(part.getString("type"))) {
                                        delta.put("content", part.getString("text"));
                                    } else if ("input_json_delta".equals(part.getString("type"))) {
                                        JSONObject function = new JSONObject();
                                        function.put("arguments", part.getString("partial_json"));
                                        JSONObject call = new JSONObject();
                                        call.put("index", Math.max(0, state.getIntValue("toolIndex") - 1));
                                        call.put("function", function);
                                        JSONArray calls = new JSONArray();
                                        calls.add(call);
                                        delta.put("tool_calls", calls);
                                    } else return;
                                    sink.next(chunk(context, delta, null, null));
                                } else if ("message_delta".equals(type)) {
                                    JSONObject usage = payload.getJSONObject("usage");
                                    if (usage != null) state.put("output", usage.getLong("output_tokens"));
                                    JSONObject delta = payload.getJSONObject("delta");
                                    String reason = delta == null ? null : delta.getString("stop_reason");
                                    sink.next(chunk(context, new JSONObject(),
                                            "tool_use".equals(reason) ? "tool_calls"
                                                    : "max_tokens".equals(reason) ? "length" : "stop", null));
                                } else if ("message_stop".equals(type)) {
                                    sink.next(chunk(context, null, null,
                                            usage(state.getLong("input"), state.getLong("output"))));
                                }
                            } catch (RuntimeException e) { sink.error(UpstreamErrors.from(502, null)); }
                        })
                        .cast(ServerSentEvent.class)
                        .map(value -> (ServerSentEvent<String>) value)
                        .onErrorMap(WebClientResponseException.class,
                                e -> UpstreamErrors.from(e.getStatusCode().value(), e.getResponseBodyAsString()))
                        .onErrorMap(WebClientRequestException.class, e -> UpstreamErrors.timeout())
                        .concatWithValues(done());
            });
        }
    }

    public static final class Ollama extends Base {
        @Override public UpstreamProtocol protocol() { return UpstreamProtocol.OLLAMA_NATIVE; }
        @Override public String provider() { return "ollama"; }
        @Override String url(ForwardContext context) { return base(context.getApiUrl()) + "/api/chat"; }
        @Override RestClient.RequestBodySpec authenticate(RestClient.RequestBodySpec request, ForwardContext context) { return request; }

        @Override JSONObject request(ForwardContext context, JSONObject input) {
            fields(input, "model", "messages", "temperature", "top_p", "max_tokens", "stream", "stop");
            for (Object item : input.getJSONArray("messages")) {
                if (!(item instanceof JSONObject message)) throw unsupported();
                fields(message, "role", "content");
                text(message);
            }
            JSONObject request = new JSONObject();
            request.put("model", context.getModelName());
            request.put("messages", input.getJSONArray("messages"));
            request.put("stream", false);
            JSONObject options = new JSONObject();
            if (input.containsKey("temperature")) options.put("temperature", input.get("temperature"));
            if (input.containsKey("top_p")) options.put("top_p", input.get("top_p"));
            if (input.containsKey("max_tokens")) options.put("num_predict", input.get("max_tokens"));
            if (input.containsKey("stop")) options.put("stop", input.get("stop"));
            if (!options.isEmpty()) request.put("options", options);
            return request;
        }

        @Override JSONObject response(ForwardContext context, JSONObject upstream) {
            JSONObject message = upstream.getJSONObject("message");
            if (message == null) throw UpstreamErrors.from(502, null);
            return openAi(context, message.getString("content"), null, "stop",
                    usage(upstream.getLong("prompt_eval_count"), upstream.getLong("eval_count")));
        }

        @Override public Flux<ServerSentEvent<String>> stream(ForwardContext context) {
            JSONObject input = JSON.parseObject(context.getBody());
            if (input == null) return Flux.error(unsupported());
            JSONObject outbound = request(context, input);
            outbound.put("stream", true);
            return Flux.defer(() -> {
                AtomicBoolean finished = new AtomicBoolean();
                AtomicBoolean started = new AtomicBoolean();
                return webClient.post().uri(url(context)).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.parseMediaType("application/x-ndjson"))
                        .bodyValue(JSON.toJSONString(outbound)).retrieve().bodyToFlux(String.class)
                        .flatMapIterable(raw -> {
                            JSONObject payload = JSON.parseObject(raw);
                            if (payload == null) return List.<ServerSentEvent<String>>of();
                            List<ServerSentEvent<String>> events = new ArrayList<>();
                            if (started.compareAndSet(false, true)) {
                                JSONObject role = new JSONObject();
                                role.put("role", "assistant");
                                events.add(chunk(context, role, null, null));
                            }
                            JSONObject message = payload.getJSONObject("message");
                            if (message != null && message.getString("content") != null) {
                                JSONObject delta = new JSONObject();
                                delta.put("content", message.getString("content"));
                                events.add(chunk(context, delta, null, null));
                            }
                            if (Boolean.TRUE.equals(payload.getBoolean("done"))) {
                                finished.set(true);
                                events.add(chunk(context, new JSONObject(), "stop", null));
                                JSONObject usage = usage(payload.getLong("prompt_eval_count"), payload.getLong("eval_count"));
                                if (usage != null) events.add(chunk(context, null, null, usage));
                            }
                            return events;
                        })
                        .onErrorMap(WebClientResponseException.class,
                                e -> UpstreamErrors.from(e.getStatusCode().value(), e.getResponseBodyAsString()))
                        .onErrorMap(WebClientRequestException.class, e -> UpstreamErrors.timeout())
                        .concatWith(Flux.defer(() -> finished.get() ? Flux.just(done())
                                : Flux.error(UpstreamErrors.from(502, null))));
            });
        }
    }

    public static final class Gemini extends Base {
        @Override public UpstreamProtocol protocol() { return UpstreamProtocol.GEMINI_GENERATE_CONTENT; }
        @Override public String provider() { return "gemini"; }
        @Override String url(ForwardContext context) {
            return base(context.getApiUrl()) + "/models/"
                    + URLEncoder.encode(context.getModelName(), StandardCharsets.UTF_8)
                    + ":generateContent";
        }
        @Override RestClient.RequestBodySpec authenticate(RestClient.RequestBodySpec request, ForwardContext context) {
            return request.header("x-goog-api-key", context.getApiKey());
        }

        @Override JSONObject request(ForwardContext context, JSONObject input) {
            fields(input, "model", "messages", "temperature", "top_p", "max_tokens", "stream", "tools", "stop");
            JSONArray contents = new JSONArray();
            StringBuilder system = new StringBuilder();
            Map<String, String> toolNames = new java.util.HashMap<>();
            for (Object item : input.getJSONArray("messages")) {
                if (!(item instanceof JSONObject message)) throw unsupported();
                String role = message.getString("role");
                if ("system".equals(role) || "developer".equals(role)) {
                    fields(message, "role", "content");
                    if (!system.isEmpty()) system.append("\n");
                    system.append(text(message));
                } else if ("user".equals(role) || "assistant".equals(role)) {
                    fields(message, "role", "content", "tool_calls");
                    JSONObject content = new JSONObject();
                    content.put("role", "assistant".equals(role) ? "model" : "user");
                    JSONArray parts = new JSONArray();
                    if (message.get("content") instanceof String value && !value.isEmpty()) {
                        JSONObject part = new JSONObject();
                        part.put("text", value);
                        parts.add(part);
                    } else if (message.get("content") != null) throw unsupported();
                    JSONArray calls = message.getJSONArray("tool_calls");
                    if (calls != null) {
                        if (!"assistant".equals(role)) throw unsupported();
                        for (Object callItem : calls) {
                            if (!(callItem instanceof JSONObject call) || !"function".equals(call.getString("type"))) throw unsupported();
                            JSONObject function = call.getJSONObject("function");
                            if (function == null) throw unsupported();
                            Object args;
                            try { args = JSON.parse(function.getString("arguments")); }
                            catch (RuntimeException e) { throw unsupported(); }
                            JSONObject functionCall = new JSONObject();
                            functionCall.put("name", function.getString("name"));
                            functionCall.put("args", args);
                            JSONObject part = new JSONObject();
                            part.put("functionCall", functionCall);
                            parts.add(part);
                            toolNames.put(call.getString("id"), function.getString("name"));
                        }
                    }
                    if (parts.isEmpty()) throw unsupported();
                    content.put("parts", parts);
                    contents.add(content);
                } else if ("tool".equals(role)) {
                    fields(message, "role", "tool_call_id", "content");
                    String name = toolNames.get(message.getString("tool_call_id"));
                    if (name == null) throw unsupported();
                    JSONObject response = new JSONObject();
                    response.put("result", text(message));
                    JSONObject functionResponse = new JSONObject();
                    functionResponse.put("name", name);
                    functionResponse.put("response", response);
                    JSONObject part = new JSONObject();
                    part.put("functionResponse", functionResponse);
                    JSONArray parts = new JSONArray();
                    parts.add(part);
                    JSONObject content = new JSONObject();
                    content.put("role", "user");
                    content.put("parts", parts);
                    contents.add(content);
                } else throw unsupported();
            }
            JSONObject request = new JSONObject();
            request.put("contents", contents);
            if (!system.isEmpty()) {
                JSONObject part = new JSONObject();
                part.put("text", system.toString());
                JSONObject instruction = new JSONObject();
                JSONArray parts = new JSONArray();
                parts.add(part);
                instruction.put("parts", parts);
                request.put("systemInstruction", instruction);
            }
            JSONObject config = new JSONObject();
            if (input.containsKey("temperature")) config.put("temperature", input.get("temperature"));
            if (input.containsKey("top_p")) config.put("topP", input.get("top_p"));
            if (input.containsKey("max_tokens")) config.put("maxOutputTokens", input.get("max_tokens"));
            if (input.containsKey("stop")) config.put("stopSequences", input.get("stop"));
            if (!config.isEmpty()) request.put("generationConfig", config);
            JSONArray tools = input.getJSONArray("tools");
            if (tools != null) {
                JSONArray declarations = new JSONArray();
                for (Object item : tools) {
                    if (!(item instanceof JSONObject tool) || !"function".equals(tool.getString("type"))) throw unsupported();
                    JSONObject function = tool.getJSONObject("function");
                    if (function == null) throw unsupported();
                    JSONObject declaration = new JSONObject();
                    declaration.put("name", function.getString("name"));
                    if (function.containsKey("description")) declaration.put("description", function.get("description"));
                    declaration.put("parameters", function.get("parameters"));
                    declarations.add(declaration);
                }
                JSONObject wrapper = new JSONObject();
                wrapper.put("functionDeclarations", declarations);
                JSONArray wrapped = new JSONArray();
                wrapped.add(wrapper);
                request.put("tools", wrapped);
            }
            return request;
        }

        @Override JSONObject response(ForwardContext context, JSONObject upstream) {
            JSONArray candidates = upstream.getJSONArray("candidates");
            if (candidates == null || candidates.isEmpty()) throw UpstreamErrors.from(502, null);
            JSONObject candidate = candidates.getJSONObject(0);
            JSONObject content = candidate.getJSONObject("content");
            JSONArray parts = content == null ? null : content.getJSONArray("parts");
            StringBuilder text = new StringBuilder();
            JSONArray calls = new JSONArray();
            if (parts != null) for (Object item : parts) {
                if (!(item instanceof JSONObject part)) continue;
                if (part.getString("text") != null) text.append(part.getString("text"));
                JSONObject functionCall = part.getJSONObject("functionCall");
                if (functionCall != null) {
                    JSONObject function = new JSONObject();
                    function.put("name", functionCall.getString("name"));
                    function.put("arguments", JSON.toJSONString(functionCall.get("args")));
                    JSONObject call = new JSONObject();
                    call.put("id", "call_" + calls.size());
                    call.put("type", "function");
                    call.put("function", function);
                    calls.add(call);
                }
            }
            JSONObject raw = upstream.getJSONObject("usageMetadata");
            String reason = candidate.getString("finishReason");
            return openAi(context, text.toString(), calls,
                    "MAX_TOKENS".equals(reason) ? "length" : calls.isEmpty() ? "stop" : "tool_calls",
                    raw == null ? null : usage(raw.getLong("promptTokenCount"), raw.getLong("candidatesTokenCount")));
        }

        @Override public Flux<ServerSentEvent<String>> stream(ForwardContext context) {
            JSONObject input = JSON.parseObject(context.getBody());
            if (input == null) return Flux.error(unsupported());
            JSONObject outbound = request(context, input);
            String streamUrl = url(context).replace(":generateContent", ":streamGenerateContent?alt=sse");
            return Flux.defer(() -> {
                AtomicBoolean finished = new AtomicBoolean();
                AtomicBoolean started = new AtomicBoolean();
                java.util.concurrent.atomic.AtomicInteger toolIndex = new java.util.concurrent.atomic.AtomicInteger();
                return webClient.post().uri(streamUrl).header("x-goog-api-key", context.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .bodyValue(JSON.toJSONString(outbound)).retrieve()
                        .bodyToFlux(new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<String>>() {})
                        .flatMapIterable(event -> {
                            JSONObject payload = JSON.parseObject(event.data());
                            if (payload == null) return List.<ServerSentEvent<String>>of();
                            List<ServerSentEvent<String>> events = new ArrayList<>();
                            if (started.compareAndSet(false, true)) {
                                JSONObject role = new JSONObject();
                                role.put("role", "assistant");
                                events.add(chunk(context, role, null, null));
                            }
                            JSONArray candidates = payload.getJSONArray("candidates");
                            if (candidates != null && !candidates.isEmpty()) {
                                JSONObject candidate = candidates.getJSONObject(0);
                                JSONObject content = candidate.getJSONObject("content");
                                JSONArray parts = content == null ? null : content.getJSONArray("parts");
                                if (parts != null) for (Object item : parts) {
                                    if (!(item instanceof JSONObject part)) continue;
                                    JSONObject delta = new JSONObject();
                                    if (part.getString("text") != null) {
                                        delta.put("content", part.getString("text"));
                                    } else if (part.getJSONObject("functionCall") != null) {
                                        JSONObject call = part.getJSONObject("functionCall");
                                        JSONObject function = new JSONObject();
                                        function.put("name", call.getString("name"));
                                        function.put("arguments", JSON.toJSONString(call.get("args")));
                                        JSONObject tool = new JSONObject();
                                        tool.put("index", toolIndex.getAndIncrement());
                                        tool.put("type", "function");
                                        tool.put("function", function);
                                        JSONArray calls = new JSONArray();
                                        calls.add(tool);
                                        delta.put("tool_calls", calls);
                                    } else continue;
                                    events.add(chunk(context, delta, null, null));
                                }
                                String reason = candidate.getString("finishReason");
                                if (reason != null) {
                                    finished.set(true);
                                    events.add(chunk(context, new JSONObject(),
                                            "MAX_TOKENS".equals(reason) ? "length" : "stop", null));
                                }
                            }
                            JSONObject metadata = payload.getJSONObject("usageMetadata");
                            if (metadata != null) {
                                JSONObject usage = usage(metadata.getLong("promptTokenCount"),
                                        metadata.getLong("candidatesTokenCount"));
                                if (usage != null) events.add(chunk(context, null, null, usage));
                            }
                            return events;
                        })
                        .onErrorMap(WebClientResponseException.class,
                                e -> UpstreamErrors.from(e.getStatusCode().value(), e.getResponseBodyAsString()))
                        .onErrorMap(WebClientRequestException.class, e -> UpstreamErrors.timeout())
                        .concatWith(Flux.defer(() -> finished.get() ? Flux.just(done())
                                : Flux.error(UpstreamErrors.from(502, null))));
            });
        }
    }

    public static final class Azure extends Base {
        private final UpstreamProtocol protocol;
        Azure(UpstreamProtocol protocol) { this.protocol = protocol; }
        @Override public UpstreamProtocol protocol() { return protocol; }
        @Override public String provider() { return "azure"; }
        @Override RestClient.RequestBodySpec authenticate(RestClient.RequestBodySpec request, ForwardContext context) {
            return request.header("api-key", context.getApiKey());
        }
        @Override String url(ForwardContext context) {
            String base = base(context.getApiUrl());
            if (protocol == UpstreamProtocol.AZURE_OPENAI_V1) {
                return (base.endsWith("/openai/v1") ? base : base + "/openai/v1") + "/chat/completions";
            }
            JSONObject settings = JSON.parseObject(context.getProviderSettings());
            if (settings == null || settings.getString("deployment") == null
                    || settings.getString("apiVersion") == null) throw unsupported();
            return base + "/openai/deployments/" + settings.getString("deployment")
                    + "/chat/completions?api-version=" + settings.getString("apiVersion");
        }
        @Override JSONObject request(ForwardContext context, JSONObject input) {
            JSONObject copy = JSON.parseObject(JSON.toJSONString(input));
            copy.put("model", protocol == UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT
                    ? JSON.parseObject(context.getProviderSettings()).getString("deployment")
                    : context.getModelName());
            return copy;
        }
        @Override JSONObject response(ForwardContext context, JSONObject upstream) { return upstream; }
        @Override public Flux<ServerSentEvent<String>> stream(ForwardContext context) {
            JSONObject input = JSON.parseObject(context.getBody());
            if (input == null) return Flux.error(unsupported());
            JSONObject outbound = request(context, input);
            outbound.put("stream", true);
            JSONObject options = outbound.getJSONObject("stream_options");
            if (options == null) options = new JSONObject();
            options.put("include_usage", true);
            outbound.put("stream_options", options);
            return webClient.post().uri(url(context)).header("api-key", context.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(JSON.toJSONString(outbound)).retrieve()
                    .bodyToFlux(new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .onErrorMap(WebClientResponseException.class,
                            e -> UpstreamErrors.from(e.getStatusCode().value(), e.getResponseBodyAsString()))
                    .onErrorMap(WebClientRequestException.class, e -> UpstreamErrors.timeout());
        }
    }

    @Configuration(proxyBeanMethods = false)
    public static class Registrations {
        @Bean public Anthropic anthropicChatAdapter() { return new Anthropic(); }
        @Bean public Ollama ollamaChatAdapter() { return new Ollama(); }
        @Bean public Gemini geminiChatAdapter() { return new Gemini(); }
        @Bean public Azure azureV1ChatAdapter() { return new Azure(UpstreamProtocol.AZURE_OPENAI_V1); }
        @Bean public Azure azureDeploymentChatAdapter() { return new Azure(UpstreamProtocol.AZURE_OPENAI_DEPLOYMENT); }
    }
}
