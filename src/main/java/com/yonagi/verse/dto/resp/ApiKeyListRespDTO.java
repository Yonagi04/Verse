package com.yonagi.verse.dto.resp;

import lombok.Data;

import java.util.Date;

/**
 * API Key 列表项（不含完整 Key）
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @date 2026/08/16
 */
@Data
public class ApiKeyListRespDTO {

    /**
     * API Key 唯一标识（业务ID）
     */
    private Long apiKeyId;

    /**
     * Key 的备注名
     */
    private String name;

    /**
     * API Key 前缀（明文，用于识别）
     */
    private String keyPrefix;

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
