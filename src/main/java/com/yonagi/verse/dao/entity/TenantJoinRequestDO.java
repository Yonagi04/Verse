package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/02 19:09
 */
@Data
@TableName("t_tenant_join_request")
public class TenantJoinRequestDO {

    /**
     * 自增主键
     */
    private Long id;

    /**
     * 申请ID，雪花算法生成
     */
    private Long requestId;

    /**
     * 目标租户 ID（业务 ID）
     */
    private Long tenantId;

    /**
     * 申请人用户 ID（业务 ID）
     */
    private Long userId;

    /**
     * 关联t_tenant_invite的id字段
     */
    private Long inviteId;

    /**
     * 状态：PENDING / APPROVED / REJECTED
     */
    private String status;

    /**
     * 审批人用户 ID（业务 ID）
     */
    private Long reviewedBy;

    /**
     * 审批备注
     */
    private String reviewComment;

    /**
     * 申请时间
     */
    private Date requestedAt;

    /**
     * 审批时间
     */
    private Date reviewedAt;
}
