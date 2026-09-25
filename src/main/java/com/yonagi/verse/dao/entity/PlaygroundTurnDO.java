package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** PlayGround 的一次用户输入与助手回复。 */
@Data
@TableName("t_playground_turn")
public class PlaygroundTurnDO {
    /** 自增主键。 */
    private Long id;
    /** 轮次业务 ID。 */
    private Long turnId;
    /** 租户业务 ID。 */
    private Long tenantId;
    /** 创建者业务 ID。 */
    private Long ownerUserId;
    /** 所属会话业务 ID。 */
    private Long sessionId;
    /** 会话内递增序号。 */
    private Integer turnNo;
    /** 客户端发送幂等键。 */
    private String idempotencyKey;
    /** 上游请求追踪 ID。 */
    private String requestId;
    /** 用户纯文本输入。 */
    private String prompt;
    /** 助手完整或部分回复。 */
    private String reply;
    /** PENDING、STREAMING、COMPLETED、STOPPED 或 FAILED。 */
    private String status;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 最近写入时间。 */
    private LocalDateTime updateTime;
    /** 终态时间。 */
    private LocalDateTime finishedAt;
}
