package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/** 单条租户动态响应。 */
@Data
public class TenantActivityItemRespDTO {

    /** 幂等事件 ID。 */
    private String eventId;

    /** 动态分类。 */
    private String category;

    /** 动态类型。 */
    private String activityType;

    /** 操作人用户 ID。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long actorUserId;

    /** 操作人用户名快照。 */
    private String actorUsername;

    /** 操作人当前昵称；已离开租户时为昵称快照。 */
    private String actorNickname;

    /** 目标对象类型。 */
    private String targetType;

    /** 目标对象业务 ID。 */
    private String targetId;

    /** 目标对象名称快照。 */
    private String targetName;

    /** 经过动态类型白名单过滤的结构化详情。 */
    private Map<String, Object> details;

    /** 业务发生时间。 */
    private LocalDateTime occurredAt;
}
