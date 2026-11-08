package com.yonagi.verse.service.playground;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ServerException;

/** 工作台历史 JSON 与具名结构之间的编解码，不改变数据库载荷格式。 */
public final class WorkbenchJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private WorkbenchJson() {
    }

    /** 读取完整历史结构；损坏数据明确失败，不返回空载荷掩盖。 */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw failure("工作台数据读取失败", ex);
        }
    }

    /** 序列化具名载荷或事件，供持久化和 SSE 使用。 */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw failure("工作台数据编码失败", ex);
        }
    }

    /** 外部协议及供应商设置仅在内部按树读取，不作为业务返回结构。 */
    public static JsonNode tree(String json) {
        return read(json, JsonNode.class);
    }

    private static ServerException failure(String message, JsonProcessingException ex) {
        // Jackson 的异常消息可能含请求快照正文；保留异常类型和原位置栈用于诊断。
        IllegalStateException diagnostic = new IllegalStateException(ex.getClass().getSimpleName());
        diagnostic.setStackTrace(ex.getStackTrace());
        return new ServerException(message, diagnostic, BaseErrorCode.SERVICE_ERROR);
    }
}
