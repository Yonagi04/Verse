package com.yonagi.verse.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.TenantActivityType;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.TenantActivityLogDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantActivityLogMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.dto.resp.TenantActivityItemRespDTO;
import com.yonagi.verse.dto.resp.TenantActivityListRespDTO;
import com.yonagi.verse.dto.resp.TenantActivityStatusRespDTO;
import com.yonagi.verse.service.TenantActivityQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 租户动态只读查询服务实现。 */
@Service
@RequiredArgsConstructor
public class TenantActivityQueryServiceImpl implements TenantActivityQueryService {

    static final int DEFAULT_LIMIT = 30;
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 50;

    private final TenantMapper tenantMapper;
    private final UserTenantMapper userTenantMapper;
    private final TenantActivityLogMapper activityLogMapper;

    @Override
    public TenantActivityStatusRespDTO getStatus(Long userId, Long tenantId) {
        TenantDO tenant = requireReadableTenant(userId, tenantId);
        return new TenantActivityStatusRespDTO(isRecordingEnabled(tenant));
    }

    @Override
    public TenantActivityListRespDTO listActivities(Long userId, Long tenantId,
                                                     Integer requestedLimit, String encodedCursor) {
        TenantDO tenant = requireReadableTenant(userId, tenantId);
        // 每一批都读取实时开关；关闭时必须在访问事实表前失败。
        if (!isRecordingEnabled(tenant)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ACTIVITY_RECORDING_DISABLED);
        }

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
        if (userId == null) {
            throw new ClientException(TenantErrorCodeEnum.USER_ID_IS_NULL);
        }
        if (tenantId == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        }
        TenantDO tenant = tenantMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0));
        if (tenant == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        }
        UserTenantDO membership = userTenantMapper.selectOne(Wrappers.lambdaQuery(UserTenantDO.class)
                .eq(UserTenantDO::getUserId, userId)
                .eq(UserTenantDO::getTenantId, tenantId)
                .isNull(UserTenantDO::getLeftAt)
                .in(UserTenantDO::getRole, "MEMBER", "ADMIN", "SUPER_ADMIN"));
        if (membership == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
        return tenant;
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
        if (encodedCursor.isBlank() || encodedCursor.length() > 512) {
            throw invalidParameter("动态查询游标无效");
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(encodedCursor), StandardCharsets.UTF_8);
            JSONObject object = JSON.parseObject(json);
            if (object == null || object.size() != 2
                    || !object.containsKey("occurredAt") || !object.containsKey("id")) {
                throw invalidParameter("动态查询游标无效");
            }
            Object occurredAtValue = object.get("occurredAt");
            Object idValue = object.get("id");
            if (!(occurredAtValue instanceof String occurredAtText)
                    || occurredAtText.isBlank()
                    || (!(idValue instanceof Long) && !(idValue instanceof Integer))) {
                throw invalidParameter("动态查询游标无效");
            }
            long id = ((Number) idValue).longValue();
            if (id <= 0) {
                throw invalidParameter("动态查询游标无效");
            }
            return new ActivityCursor(LocalDateTime.parse(occurredAtText), id);
        } catch (ClientException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidParameter("动态查询游标无效");
        }
    }

    private String encodeCursor(TenantActivityLogDO row) {
        if (row.getId() == null || row.getOccurredAt() == null) {
            throw new IllegalStateException("租户动态缺少稳定游标字段");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("occurredAt", row.getOccurredAt().toString());
        value.put("id", row.getId());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                JSON.toJSONString(value).getBytes(StandardCharsets.UTF_8));
    }

    private TenantActivityItemRespDTO toResponse(TenantActivityLogDO row) {
        TenantActivityItemRespDTO response = new TenantActivityItemRespDTO();
        response.setEventId(row.getEventId());
        response.setCategory(row.getCategory());
        response.setActivityType(row.getActivityType());
        response.setActorUserId(row.getActorUserId());
        response.setActorUsername(row.getActorUsername());
        // 操作人仍是当前租户成员时展示实时昵称，否则保留事件发生时的昵称快照。
        response.setActorNickname(row.getCurrentActorNickname() != null
                ? row.getCurrentActorNickname() : row.getActorNickname());
        response.setTargetType(row.getTargetType());
        response.setTargetId(row.getTargetId());
        response.setTargetName(row.getTargetName());
        response.setDetails(parseWhitelistedDetails(row.getActivityType(), row.getDetailJson()));
        response.setOccurredAt(row.getOccurredAt());
        return response;
    }

    private Map<String, Object> parseWhitelistedDetails(String activityType, String detailJson) {
        if (activityType == null || detailJson == null || detailJson.isBlank()) {
            return Collections.emptyMap();
        }
        final TenantActivityType type;
        try {
            type = TenantActivityType.valueOf(activityType);
        } catch (IllegalArgumentException exception) {
            return Collections.emptyMap();
        }
        JSONObject source;
        try {
            source = JSON.parseObject(detailJson);
        } catch (RuntimeException exception) {
            return Collections.emptyMap();
        }
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> allowedKeys = type.allowedDetailKeys();
        Map<String, Object> details = new LinkedHashMap<>();
        for (String key : allowedKeys) {
            if (source.containsKey(key)) {
                details.put(key, source.get(key));
            }
        }
        return details;
    }

    private ClientException invalidParameter(String message) {
        return new ClientException(message, BaseErrorCode.CLIENT_ERROR);
    }

    private record ActivityCursor(LocalDateTime occurredAt, Long id) {
    }
}
