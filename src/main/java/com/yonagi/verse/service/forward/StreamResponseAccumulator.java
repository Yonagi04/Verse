package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aggregates OpenAI-compatible chat-completion SSE chunks into a response shaped like a
 * non-streaming chat completion. One instance belongs to one stream subscription.
 */
public final class StreamResponseAccumulator {

    private static final String DONE = "[DONE]";

    private final boolean captureResponse;
    private final Map<String, Object> metadata = new LinkedHashMap<>();
    private final Map<Integer, ChoiceAccumulator> choices = new TreeMap<>();

    private JSONObject usage;
    private boolean validResponseChunk;

    public StreamResponseAccumulator(boolean captureResponse) {
        this.captureResponse = captureResponse;
    }

    /**
     * Observes one SSE data payload. Invalid JSON and the terminal {@code [DONE]} marker are ignored.
     */
    public void accept(String data) {
        if (!hasText(data) || DONE.equals(data.trim())) {
            return;
        }

        JSONObject chunk;
        try {
            chunk = JSON.parseObject(data);
        } catch (Exception ignored) {
            return;
        }
        if (chunk == null) {
            return;
        }

        JSONObject chunkUsage = chunk.getJSONObject("usage");
        if (chunkUsage != null && !chunkUsage.isEmpty()) {
            usage = chunkUsage;
        }

        if (!captureResponse) {
            return;
        }

        JSONArray chunkChoices = chunk.getJSONArray("choices");
        boolean recognized = containsAny(chunk, "id", "created", "model", "system_fingerprint",
                "service_tier", "usage") || chunkChoices != null;
        if (!recognized) {
            return;
        }
        validResponseChunk = true;

        copyMetadata(chunk, "id");
        copyMetadata(chunk, "created");
        copyMetadata(chunk, "model");
        copyMetadata(chunk, "system_fingerprint");
        copyMetadata(chunk, "service_tier");

        if (chunkChoices == null) {
            return;
        }
        for (int i = 0; i < chunkChoices.size(); i++) {
            JSONObject choice = chunkChoices.getJSONObject(i);
            if (choice == null) {
                continue;
            }
            Integer index = choice.getInteger("index");
            int choiceIndex = index == null ? i : index;
            choices.computeIfAbsent(choiceIndex, ChoiceAccumulator::new).accept(choice);
        }
    }

    public JSONObject usage() {
        return usage;
    }

    /**
     * Builds an immutable JSON string for MQ publication, or {@code null} if no valid response
     * chunk was observed (or response capture was disabled).
     */
    public String buildResponseJson() {
        if (!captureResponse || !validResponseChunk) {
            return null;
        }

        JSONObject response = new JSONObject();
        metadata.forEach(response::put);
        response.put("object", "chat.completion");

        JSONArray responseChoices = new JSONArray();
        choices.values().forEach(choice -> responseChoices.add(choice.toJson()));
        response.put("choices", responseChoices);
        if (usage != null) {
            response.put("usage", usage);
        }
        return JSON.toJSONString(response);
    }

    private void copyMetadata(JSONObject chunk, String key) {
        if (chunk.containsKey(key)) {
            metadata.put(key, chunk.get(key));
        }
    }

    private boolean containsAny(JSONObject json, String... keys) {
        for (String key : keys) {
            if (json.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class ChoiceAccumulator {

        private final int index;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder refusal = new StringBuilder();
        private final Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>();

        private String role;
        private boolean contentSeen;
        private boolean refusalSeen;
        private Object logprobs;
        private String finishReason;

        private ChoiceAccumulator(int index) {
            this.index = index;
        }

        private void accept(JSONObject choice) {
            JSONObject delta = choice.getJSONObject("delta");
            if (delta != null) {
                String deltaRole = delta.getString("role");
                if (hasText(deltaRole)) {
                    role = deltaRole;
                }

                Object deltaContent = delta.get("content");
                if (deltaContent instanceof String text) {
                    contentSeen = true;
                    content.append(text);
                }

                Object deltaRefusal = delta.get("refusal");
                if (deltaRefusal instanceof String text) {
                    refusalSeen = true;
                    refusal.append(text);
                }

                JSONArray deltaToolCalls = delta.getJSONArray("tool_calls");
                if (deltaToolCalls != null) {
                    for (int i = 0; i < deltaToolCalls.size(); i++) {
                        JSONObject toolCall = deltaToolCalls.getJSONObject(i);
                        if (toolCall == null) {
                            continue;
                        }
                        Integer toolIndex = toolCall.getInteger("index");
                        int resolvedIndex = toolIndex == null ? i : toolIndex;
                        toolCalls.computeIfAbsent(resolvedIndex, ignored -> new ToolCallAccumulator())
                                .accept(toolCall);
                    }
                }
            }

            if (choice.containsKey("finish_reason") && choice.get("finish_reason") != null) {
                finishReason = choice.getString("finish_reason");
            }
            if (choice.containsKey("logprobs")) {
                logprobs = choice.get("logprobs");
            }
        }

        private JSONObject toJson() {
            JSONObject message = new JSONObject();
            message.put("role", hasText(role) ? role : "assistant");
            message.put("content", contentSeen ? content.toString() : null);
            if (refusalSeen) {
                message.put("refusal", refusal.toString());
            }
            if (!toolCalls.isEmpty()) {
                JSONArray calls = new JSONArray();
                toolCalls.values().forEach(toolCall -> calls.add(toolCall.toJson()));
                message.put("tool_calls", calls);
            }

            JSONObject choice = new JSONObject();
            choice.put("index", index);
            choice.put("message", message);
            choice.put("finish_reason", finishReason);
            if (logprobs != null) {
                choice.put("logprobs", logprobs);
            }
            return choice;
        }
    }

    private static final class ToolCallAccumulator {

        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        private String id;
        private String type;
        private boolean functionSeen;

        private void accept(JSONObject toolCall) {
            String toolId = toolCall.getString("id");
            if (hasText(toolId)) {
                id = toolId;
            }
            String toolType = toolCall.getString("type");
            if (hasText(toolType)) {
                type = toolType;
            }

            JSONObject function = toolCall.getJSONObject("function");
            if (function == null) {
                return;
            }
            functionSeen = true;
            String functionName = function.getString("name");
            if (functionName != null) {
                name.append(functionName);
            }
            String functionArguments = function.getString("arguments");
            if (functionArguments != null) {
                arguments.append(functionArguments);
            }
        }

        private JSONObject toJson() {
            JSONObject toolCall = new JSONObject();
            if (id != null) {
                toolCall.put("id", id);
            }
            if (type != null) {
                toolCall.put("type", type);
            }
            if (functionSeen) {
                JSONObject function = new JSONObject();
                function.put("name", name.toString());
                function.put("arguments", arguments.toString());
                toolCall.put("function", function);
            }
            return toolCall;
        }
    }
}
