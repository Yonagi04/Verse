package com.yonagi.verse.async.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEventHandler;
import com.yonagi.verse.async.event.LlmAuditEvent;
import com.yonagi.verse.dao.entity.LlmAuditLogDO;
import com.yonagi.verse.dao.mapper.LlmAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * LLM 审计事件消费者 — 将输入/输出上传 S3，提取概略，落 t_llm_audit_log。
 * S3 上传失败 best-effort：仍落库，objectKey 置 NULL。
 *
 * @author Yonagi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmAuditEventHandler implements DomainEventHandler<LlmAuditEvent> {

    private final LlmAuditLogMapper llmAuditLogMapper;
    private final S3Client s3Client;

    @Value("${verse.s3.bucket}")
    private String bucket;

    @Value("${verse.llm.audit.preview-length:20}")
    private int previewLength;

    @Override
    public String eventType() {
        return EventTag.LLM_AUDIT;
    }

    @Override
    public Class<LlmAuditEvent> eventClass() {
        return LlmAuditEvent.class;
    }

    @Override
    public void onEvent(LlmAuditEvent event) {
        String promptKey = uploadJson(event.getPrompt(), buildKey(event, "prompt.json"));
        String responseKey = uploadJson(event.getResponse(), buildKey(event, "response.json"));

        LlmAuditLogDO auditLog = new LlmAuditLogDO();
        auditLog.setRequestId(event.getRequestId());
        auditLog.setTenantId(event.getTenantId());
        auditLog.setUserId(event.getUserId());
        auditLog.setApiKeyId(event.getApiKeyId());
        auditLog.setServiceId(event.getServiceId());
        auditLog.setModel(event.getModel());
        auditLog.setPromptPreview(extractPromptPreview(event.getPrompt()));
        auditLog.setResponsePreview(extractResponsePreview(event.getResponse()));
        auditLog.setPromptObjectKey(promptKey);
        auditLog.setResponseObjectKey(responseKey);
        auditLog.setPromptTokens(event.getPromptTokens());
        auditLog.setCompletionTokens(event.getCompletionTokens());
        auditLog.setTotalTokens(event.getTotalTokens());
        auditLog.setLatencyMs(event.getLatencyMs());
        auditLog.setStatus(event.getStatus());
        auditLog.setErrorCode(event.getErrorCode());
        auditLog.setCreateTime(new Date());
        llmAuditLogMapper.insert(auditLog);
    }

    /**
     * 上传内容为 JSON 对象到 S3，返回 objectKey；内容为空或上传失败返回 null。
     */
    private String uploadJson(String content, String objectKey) {
        if (content == null) {
            return null;
        }
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType("application/json")
                            .build(),
                    RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8)));
            return objectKey;
        } catch (Exception e) {
            log.error("[llm-audit] 上传 S3 失败: key={}", objectKey, e);
            return null;
        }
    }

    private String buildKey(LlmAuditEvent event, String fileName) {
        return "llm-audit/" + event.getTenantId() + "/" + event.getRequestId() + "/" + fileName;
    }

    /**
     * 提取输入概略：请求体 messages 中最后一条 user 消息内容。
     */
    private String extractPromptPreview(String body) {
        if (body == null) {
            return "";
        }
        try {
            JSONObject json = JSON.parseObject(body);
            JSONArray messages = json == null ? null : json.getJSONArray("messages");
            if (messages == null || messages.isEmpty()) {
                return "";
            }
            for (int i = messages.size() - 1; i >= 0; i--) {
                JSONObject message = messages.getJSONObject(i);
                if (message != null && "user".equals(message.getString("role"))) {
                    return truncate(contentToText(message.get("content")));
                }
            }
            return "";
        } catch (Exception e) {
            log.debug("[llm-audit] 提取 prompt 概略失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 提取输出概略：响应 choices[0].message.content。
     */
    private String extractResponsePreview(String body) {
        if (body == null) {
            return "";
        }
        try {
            JSONObject json = JSON.parseObject(body);
            if (json == null) {
                return "";
            }
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return "";
            }
            JSONObject first = choices.getJSONObject(0);
            JSONObject message = first == null ? null : first.getJSONObject("message");
            if (message == null) {
                return "";
            }
            return truncate(message.getString("content"));
        } catch (Exception e) {
            log.debug("[llm-audit] 提取 response 概略失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 将消息 content（可能是字符串或多模态数组）规约为纯文本。
     */
    private String contentToText(Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof JSONArray parts) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                Object part = parts.get(i);
                if (part instanceof JSONObject p && "text".equals(p.getString("type"))) {
                    String text = p.getString("text");
                    if (text != null) {
                        sb.append(text);
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.trim();
        if (clean.length() <= previewLength) {
            return clean;
        }
        return clean.substring(0, previewLength) + "...";
    }
}
