package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 每栏每次尝试独立保存，重试不覆盖原记录。 */
@Data
@TableName("t_playground_attempt")
public class PlaygroundAttemptDO {
    /** 自增主键。 */ private Long id;
    /** 尝试业务 ID。 */ private Long attemptId;
    /** 所属租户。 */ private Long tenantId;
    /** 创建者。 */ private Long ownerUserId;
    /** 所属工作区。 */ private Long workspaceId;
    /** 组级轮次幂等键。 */ private String roundId;
    /** 工作区内轮次序号。 */ private Integer roundNo;
    /** 稳定栏位 ID。 */ private String laneId;
    /** 栏位尝试序号。 */ private Integer attemptNo;
    /** 唯一调用追踪 ID。 */ private String requestId;
    /** 本次模型服务 ID。 */ private Long serviceId;
    /** 原用户问题。 */ private String prompt;
    /** 完整或部分回答。 */ private String reply;
    /** PENDING、STREAMING、COMPLETED、STOPPED、FAILED。 */ private String status;
    /** 不可变请求及配置快照。 */ private String snapshot;
    /** 安全错误及重试时间。 */ private String errorJson;
    /** 首段有效内容延迟毫秒。 */ private Long firstContentMs;
    /** 调用总耗时毫秒。 */ private Long durationMs;
    /** 创建时间。 */ private LocalDateTime createTime;
    /** 最近活动时间。 */ private LocalDateTime updateTime;
    /** 终态时间。 */ private LocalDateTime finishedAt;
}
