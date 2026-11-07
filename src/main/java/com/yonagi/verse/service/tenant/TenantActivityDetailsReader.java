package com.yonagi.verse.service.tenant;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.yonagi.verse.common.enums.TenantActivityType;
import com.yonagi.verse.dto.resp.TenantActivityDetailRespDTO;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 读取已持久化的事件快照，仅投影事件类型允许公开的字段。 */
public final class TenantActivityDetailsReader {
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private TenantActivityDetailsReader() { }

    /** 历史快照缺失或格式损坏时，仍返回前端契约要求的空详情。 */
    public static TenantActivityDetailRespDTO read(String activityType, String json) {
        TenantActivityDetailRespDTO details = new TenantActivityDetailRespDTO();
        if (activityType == null || json == null || json.isBlank()) {
            return details;
        }
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            Set<String> allowedKeys = TenantActivityType.valueOf(activityType).allowedDetailKeys();
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return details;
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                parser.nextToken();
                if (allowedKeys.contains(name)) {
                    readField(details, name, parser);
                }
                parser.skipChildren();
            }
            if (parser.currentToken() == JsonToken.END_OBJECT && parser.nextToken() == null) {
                return details;
            }
        } catch (IOException | IllegalArgumentException exception) {
            // 详情损坏不应阻断整页事件读取。
        }
        return new TenantActivityDetailRespDTO();
    }

    private static void readField(TenantActivityDetailRespDTO details, String name, JsonParser parser)
            throws IOException {
        switch (name) {
            case "changedFields" -> details.setChangedFields(readChangedFields(parser));
            case "joinSource" -> details.setJoinSource(text(parser));
            case "oldRole" -> details.setOldRole(text(parser));
            case "newRole" -> details.setNewRole(text(parser));
            case "status" -> details.setStatus(text(parser));
            case "expiresAt" -> details.setExpiresAt(text(parser));
            case "period" -> details.setPeriod(text(parser));
            case "periodStart" -> details.setPeriodStart(text(parser));
            case "periodEnd" -> details.setPeriodEnd(text(parser));
            case "triggeredAt" -> details.setTriggeredAt(text(parser));
            case "retryAt" -> details.setRetryAt(text(parser));
            case "provider" -> details.setProvider(text(parser));
            case "modelName" -> details.setModelName(text(parser));
            case "credentialChanged" -> {
                if (parser.currentToken() == JsonToken.VALUE_TRUE || parser.currentToken() == JsonToken.VALUE_FALSE) {
                    details.setCredentialChanged(parser.getBooleanValue());
                }
            }
            default -> { }
        }
    }

    private static String text(JsonParser parser) throws IOException {
        return parser.currentToken() == JsonToken.VALUE_STRING ? parser.getText() : null;
    }

    private static List<String> readChangedFields(JsonParser parser) throws IOException {
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        boolean valid = true;
        JsonToken token;
        while ((token = parser.nextToken()) != null && token != JsonToken.END_ARRAY) {
            if (token == JsonToken.VALUE_STRING) {
                fields.add(parser.getText());
            } else {
                valid = false;
                parser.skipChildren();
            }
        }
        return valid && token == JsonToken.END_ARRAY ? List.copyOf(fields) : null;
    }
}
