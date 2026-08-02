package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 租户邀请实体
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Data
@TableName("t_tenant_invite")
public class TenantInviteDO {

    /**
     * 自增主键
     */
    private Long id;

    /**
     * 租户ID（业务ID）
     */
    private Long tenantId;

    /**
     * 邀请码（UUID前8位大写）
     */
    private String code;

    /**
     * 创建者用户ID
     */
    private Long createdBy;

    /**
     * 过期时间
     */
    private Date expiresAt;

    /**
     * 是否有效：0=已失效, 1=有效
     */
    private Integer isActive;

    /**
     * 通过此邀请码/链接加入的人数
     */
    private Integer usageCount;

    /**
     * 创建时间
     */
    private Date createTime;
}
