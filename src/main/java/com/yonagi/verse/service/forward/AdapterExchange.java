package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.enums.ModelOperation;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/** 各能力共用的有类型传输契约，避免把音频或事件流序列化成字符串。 */
public final class AdapterExchange {
    private AdapterExchange() {}

    public sealed interface Request permits JsonRequest, MultipartRequest, BinaryRequest {
        ModelOperation operation();
        MediaType contentType();
    }

    /** 已解析、可校验的 JSON 输入。 */
    public record JsonRequest(ModelOperation operation, JSONObject body) implements Request {
        @Override public MediaType contentType() { return MediaType.APPLICATION_JSON; }
    }

    /** 带有界文件内容的 multipart 输入。 */
    public record MultipartRequest(ModelOperation operation, Map<String, String> fields,
                                   String filename, MediaType fileType, byte[] file) implements Request {
        @Override public MediaType contentType() { return MediaType.MULTIPART_FORM_DATA; }
        public MultipartRequest {
            fields = Map.copyOf(fields);
            file = file.clone();
        }
        @Override public byte[] file() { return file.clone(); }
    }

    /** 二进制输入，仅供明确支持的能力使用。 */
    public record BinaryRequest(ModelOperation operation, MediaType contentType, byte[] body) implements Request {
        public BinaryRequest { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }

    /** 无可用证据时字段为空；不得把缺失 Token 当成精确零值。 */
    public record UsageEvidence(Long inputTokens, Long outputTokens, Long totalTokens,
                                Integer imageCount, Long audioDurationMs, Integer rerankDocumentCount,
                                JSONObject raw) {
        public static UsageEvidence unknown() {
            return new UsageEvidence(null, null, null, null, null, null, null);
        }
    }

    public sealed interface Result permits JsonResult, BinaryResult, EventResult {
        MediaType contentType();
        UsageEvidence usage();
        int upstreamStatus();
    }

    public record JsonResult(JSONObject body, MediaType contentType, UsageEvidence usage,
                             int upstreamStatus, Map<String, String> auditMetadata) implements Result {
        public JsonResult { auditMetadata = Map.copyOf(auditMetadata); }
    }

    public record BinaryResult(byte[] body, MediaType contentType, UsageEvidence usage,
                               int upstreamStatus, Map<String, String> auditMetadata) implements Result {
        public BinaryResult {
            body = body.clone();
            auditMetadata = Map.copyOf(auditMetadata);
        }
        @Override public byte[] body() { return body.clone(); }
    }

    public record EventResult(Flux<ServerSentEvent<String>> events, MediaType contentType,
                              UsageEvidence usage, int upstreamStatus,
                              Map<String, String> auditMetadata) implements Result {
        public EventResult { auditMetadata = Map.copyOf(auditMetadata); }
    }
}
