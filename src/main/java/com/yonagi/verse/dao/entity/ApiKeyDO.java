package com.yonagi.verse.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * API Key 实体
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Data
@TableName("t_api_key")
public class ApiKeyDO {

    /**
     * 自增主键
     */
    private Long id;

    /**
     * API Key唯一标识（业务ID）
     */
    private Long apiKeyId;

    /**
     * 用户ID（业务ID）
     */
    private Long userId;

    /**
     * 租户ID（业务ID）
     */
    private Long tenantId;

    /**
     * API Key（SHA-256哈希存储）
     */
    private String apiKey;

    /**
     * API Key前缀（明文，用于识别）
     */
    private String keyPrefix;

    /**
     * Key的备注名
     */
    private String name;

    /**
     * 状态：0=已吊销, 1=正常
     */
    private Integer status;

    /**
     * 最近使用时间
     */
    private Date lastUsedAt;

    /**
     * 过期时间（NULL=永不过期）
     */
    private Date expiresAt;

    /**
     * 创建时间
     */
    private Date createTime;
}
