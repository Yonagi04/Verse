package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 租户动态不可变事实。 */
@Data
@TableName("t_tenant_activity_log")
public class TenantActivityLogDO {
    /** 自增主键。 */ private Long id;
    /** 幂等事件 ID。 */ private String eventId;
    /** 租户 ID。 */ private Long tenantId;
    /** 动态分类。 */ private String category;
    /** 动态类型。 */ private String activityType;
    /** 操作人用户 ID。 */ private Long actorUserId;
    /** 操作人用户名快照。 */ private String actorUsername;
    /** 操作人昵称快照。 */ private String actorNickname;
    /** 当前租户成员的实时昵称，仅用于时间线查询投影。 */
    @TableField(exist = false)
    private String currentActorNickname;
    /** 目标对象类型。 */ private String targetType;
    /** 目标对象业务 ID。 */ private String targetId;
    /** 目标对象名称快照。 */ private String targetName;
    /** 白名单化详情 JSON。 */ private String detailJson;
    /** 事件结构版本。 */ private Integer schemaVersion;
    /** 业务发生时间。 */ private LocalDateTime occurredAt;
    /** 消费落库时间。 */ private LocalDateTime createTime;
}
