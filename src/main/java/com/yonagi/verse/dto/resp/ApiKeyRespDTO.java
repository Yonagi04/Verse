package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.Date;

/**
 * API Key 创建响应（仅创建时一次性返回完整 Key）
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @date 2026/08/16
 */
@Data
public class ApiKeyRespDTO {

    /**
     * API Key 唯一标识（业务ID）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long apiKeyId;

    /**
     * Key 的备注名
     */
    private String name;

    /**
     * 过期时间（NULL=永不过期）
     */
    private Date expiresAt;

    /**
     * 完整 API Key，仅在创建时返回，之后不可再获取
     */
    private String apiKey;

    /**
     * 固定文案
     */
    private String createKeyMessage;

    /**
     * 固定文案
     */
    private String createKeyTip;
}
