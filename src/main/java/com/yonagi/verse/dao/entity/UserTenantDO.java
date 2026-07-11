package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 用户-租户关联实体
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Data
@TableName("t_user_tenant")
public class UserTenantDO {

    /**
     * 自增主键
     */
    private Long id;

    /**
     * 用户ID（业务ID）
     */
    private Long userId;

    /**
     * 租户ID（业务ID）
     */
    private Long tenantId;

    /**
     * 角色：SUPER_ADMIN / ADMIN / MEMBER
     */
    private String role;

    /**
     * 加入时间
     */
    private Date joinedAt;

    /**
     * 最近一次切换至该租户的时间
     */
    private Date lastAccessedAt;

    /**
     * 离开时间（NULL=仍在租户内）
     */
    private Date leftAt;
}
