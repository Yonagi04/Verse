package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 租户实体
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Data
@TableName("t_tenant")
public class TenantDO {

    /**
     * 自增主键
     */
    private Long id;

    /**
     * 租户唯一标识（业务ID）
     */
    private Long tenantId;

    /**
     * 租户名称
     */
    private String name;

    /**
     * 类型：PERSONAL / TEAM
     */
    private String type;

    /**
     * 创建者用户ID
     */
    private Long ownerId;

    /**
     * 租户描述
     */
    private String description;

    /**
     * 状态：0=停用, 1=正常
     */
    private Integer status;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 逻辑删除
     */
    private Integer delFlag;
}
