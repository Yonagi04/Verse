package com.yonagi.verse.service.playground;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;

import java.io.IOException;

/** 一期会话只读取 OpenAI Chat 增量中的首选纯文本回复。 */
public final class PlaygroundChatDeltaReader {
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private PlaygroundChatDeltaReader() { }

    /** 心跳和无正文帧返回 null，工具调用和非纯文本正文按不支持拒绝。 */
    public static String text(String data) {
        if (data == null || "[DONE]".equals(data.trim())) {
            return null;
        }
        try (JsonParser parser = JSON_FACTORY.createParser(data)) {
            JsonToken token = parser.nextToken();
            if (token == null || token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token != JsonToken.START_OBJECT) {
                throw upstreamError();
            }
            ChoiceDTO choice = null;
            boolean error = false;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if ("error".equals(field)) {
                    error = true;
                } else if ("choices".equals(field)) {
                    choice = readFirstChoice(parser);
                }
                parser.skipChildren();
            }
            if (error || parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null) {
                throw upstreamError();
            }
            if (choice == null) {
                return null;
            }
            if (choice.unsupported()) {
                throw new ClientException(PlaygroundErrorCodeEnum.UNSUPPORTED_RESPONSE);
            }
            return choice.text();
        } catch (IOException exception) {
            throw upstreamError();
        }
    }

    private static ChoiceDTO readFirstChoice(JsonParser parser) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            throw upstreamError();
        }
        JsonToken token = parser.nextToken();
        if (token == JsonToken.END_ARRAY) {
            return null;
        }
        ChoiceDTO choice = token == JsonToken.VALUE_NULL ? null : readChoice(parser);
        while ((token = parser.nextToken()) != null && token != JsonToken.END_ARRAY) {
            parser.skipChildren();
        }
        return choice;
    }

    private static ChoiceDTO readChoice(JsonParser parser) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw upstreamError();
        }
        boolean toolFinish = false;
        ChoiceDTO delta = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            JsonToken token = parser.nextToken();
            if ("finish_reason".equals(name)) {
                toolFinish = token == JsonToken.VALUE_STRING && isToolField(parser.getText());
            } else if ("delta".equals(name)) {
                delta = token == JsonToken.VALUE_NULL ? null : readDelta(parser);
            }
            parser.skipChildren();
        }
        return toolFinish ? new ChoiceDTO(null, true) : delta;
    }

    private static ChoiceDTO readDelta(JsonParser parser) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw upstreamError();
        }
        String text = null;
        boolean toolField = false;
        boolean nonTextContent = false;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            JsonToken token = parser.nextToken();
            if ("content".equals(name)) {
                // 显式的 null 正文和工具字段与缺省字段语义不同。
                nonTextContent = token != JsonToken.VALUE_STRING;
                text = nonTextContent ? null : parser.getText();
            } else if (isToolField(name)) {
                toolField = true;
            }
            parser.skipChildren();
        }
        return new ChoiceDTO(text, toolField || nonTextContent);
    }

    private static boolean isToolField(String name) {
        return "tool_calls".equals(name) || "function_call".equals(name);
    }

    private static ClientException upstreamError() {
        return new ClientException(PlaygroundErrorCodeEnum.UPSTREAM_ERROR);
    }

    private record ChoiceDTO(
            /** 可向客户端发送的纯文本增量。 */ String text,
            /** 是否出现一期会话无法展示的正文或工具调用。 */ boolean unsupported) { }
}
