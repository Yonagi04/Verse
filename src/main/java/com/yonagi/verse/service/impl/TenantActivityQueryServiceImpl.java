package com.yonagi.verse.service.impl;

import com.yonagi.verse.service.tenant.TenantActivityAccessPolicy;
import com.yonagi.verse.service.tenant.TenantActivityDetailsReader;
import com.yonagi.verse.service.tenant.TenantQueryAccess;

import com.yonagi.verse.common.cache.QueryCached;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.TenantActivityLogDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.TenantActivityLogMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.dto.resp.TenantActivityItemRespDTO;
import com.yonagi.verse.dto.resp.TenantActivityListRespDTO;
import com.yonagi.verse.dto.resp.TenantActivityStatusRespDTO;
import com.yonagi.verse.service.TenantActivityQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;

import static com.yonagi.verse.common.cache.QueryCacheTtl.*;
import static com.yonagi.verse.common.constant.RedisKeyConstant.*;

/** 租户动态只读查询服务实现。 */
@Service
@RequiredArgsConstructor
public class TenantActivityQueryServiceImpl implements TenantActivityQueryService {

    static final int DEFAULT_LIMIT = 30;
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 50;
    private static final int MAX_CURSOR_LENGTH = 512;
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private final TenantActivityAccessPolicy activityAccess;
    private final TenantMapper tenantMapper;
    private final UserTenantMapper userTenantMapper;
    private final TenantActivityLogMapper activityLogMapper;

    @Override
    @QueryCached(keyPrefix = TENANT_ACTIVITY_STATUS_KEY, seconds = HOURS_4, access = TenantQueryAccess.class,
            tables = {"t_tenant", "t_user_tenant"})
    public TenantActivityStatusRespDTO getStatus(Long userId, Long tenantId) {
        TenantDO tenant = requireReadableTenant(userId, tenantId);
        return new TenantActivityStatusRespDTO(isRecordingEnabled(tenant));
    }

    @Override
    @QueryCached(keyPrefix = TENANT_ACTIVITY_LIST_KEY, seconds = MINUTES_30, access = TenantActivityAccessPolicy.class,
            tables = {"t_tenant", "t_user_tenant", "t_user", "t_tenant_activity_log"})
    public TenantActivityListRespDTO listActivities(Long userId, Long tenantId,
                                                     Integer requestedLimit, String encodedCursor) {
        activityAccess.requireRecording(userId, tenantId);

        int limit = validateLimit(requestedLimit);
        ActivityCursor cursor = parseCursor(encodedCursor);
        List<TenantActivityLogDO> rows = activityLogMapper.selectTimeline(
                tenantId,
                cursor == null ? null : cursor.occurredAt(),
                cursor == null ? null : cursor.id(),
                limit + 1);

        boolean hasMore = rows.size() > limit;
        List<TenantActivityLogDO> currentRows = hasMore ? rows.subList(0, limit) : rows;
        List<TenantActivityItemRespDTO> items = currentRows.stream().map(this::toResponse).toList();
        String nextCursor = hasMore && !currentRows.isEmpty()
                ? encodeCursor(currentRows.get(currentRows.size() - 1)) : null;
        return new TenantActivityListRespDTO(items, nextCursor, hasMore);
    }

    private TenantDO requireReadableTenant(Long userId, Long tenantId) {
        return activityAccess.requireReadable(userId, tenantId);
    }

    private boolean isRecordingEnabled(TenantDO tenant) {
        return Integer.valueOf(1).equals(tenant.getActivityRecordingEnabled());
    }

    private int validateLimit(Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw invalidParameter("动态查询批量大小必须在1到50之间");
        }
        return limit;
    }

    private ActivityCursor parseCursor(String encodedCursor) {
        if (encodedCursor == null) {
            return null;
        }
        if (encodedCursor.isBlank() || encodedCursor.length() > MAX_CURSOR_LENGTH) {
            throw invalidParameter("动态查询游标无效");
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(encodedCursor), StandardCharsets.UTF_8);
            return readCursor(json);
        } catch (IOException | IllegalArgumentException | DateTimeParseException exception) {
            throw invalidParameter("动态查询游标无效");
        }
    }

    private ActivityCursor readCursor(String json) throws IOException {
        String occurredAt = null;
        Long id = null;
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            if (parser.nextToken() == JsonToken.START_OBJECT) {
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String name = parser.currentName();
                    JsonToken token = parser.nextToken();
                    if ("occurredAt".equals(name) && token == JsonToken.VALUE_STRING) {
                        occurredAt = parser.getText();
                    } else if ("id".equals(name) && token == JsonToken.VALUE_NUMBER_INT) {
                        id = parser.getLongValue();
                    } else {
                        throw invalidParameter("动态查询游标无效");
                    }
                }
                if (parser.currentToken() == JsonToken.END_OBJECT && parser.nextToken() == null
                        && occurredAt != null && !occurredAt.isBlank() && id != null && id > 0) {
                    return new ActivityCursor(LocalDateTime.parse(occurredAt), id);
                }
            }
        }
        throw invalidParameter("动态查询游标无效");
    }

    private String encodeCursor(TenantActivityLogDO row) {
        if (row.getId() == null || row.getOccurredAt() == null) {
            throw new IllegalStateException("租户动态缺少稳定游标字段");
        }
        CursorDTO value = new CursorDTO(row.getOccurredAt().toString(), row.getId());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                JSON.toJSONString(value).getBytes(StandardCharsets.UTF_8));
    }

    private TenantActivityItemRespDTO toResponse(TenantActivityLogDO row) {
        TenantActivityItemRespDTO response = new TenantActivityItemRespDTO();
        response.setEventId(row.getEventId());
        response.setCategory(row.getCategory());
        response.setActivityType(row.getActivityType());
        response.setActorType(row.getActorType() == null ? "USER" : row.getActorType());
        response.setActorUserId(row.getActorUserId());
        response.setActorUsername(row.getActorUsername());
        // 操作人仍是当前租户成员时展示实时昵称，否则保留事件发生时的昵称快照。
        response.setActorNickname("SYSTEM".equals(row.getActorType()) ? "系统" : row.getCurrentActorNickname() != null
                ? row.getCurrentActorNickname() : row.getActorNickname());
        response.setTargetType(row.getTargetType());
        response.setTargetId(row.getTargetId());
        response.setTargetName(row.getTargetName());
        response.setDetails(TenantActivityDetailsReader.read(row.getActivityType(), row.getDetailJson()));
        response.setOccurredAt(row.getOccurredAt());
        return response;
    }

    private ClientException invalidParameter(String message) {
        return new ClientException(message, BaseErrorCode.CLIENT_ERROR);
    }

    private record ActivityCursor(LocalDateTime occurredAt, Long id) {
    }

    /** Base64 游标的 JSON 表示，字段顺序沿用已发放的游标。 */
    public record CursorDTO(
            /** 排序元组中的业务发生时间。 */ @JSONField(ordinal = 1) String occurredAt,
            /** 时间相同的事件使用行 ID 排序。 */ @JSONField(ordinal = 2) Long id) { }
}
