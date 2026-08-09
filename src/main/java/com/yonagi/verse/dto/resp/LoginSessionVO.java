package com.yonagi.verse.dto.resp;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 登录会话信息 — 存储在 Redis 中的用户会话
 *
 * @author Yonagi
 */
@Data
@Builder
public class LoginSessionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String username;
    private String token;
    private Date expiresAt;
    private Long lastActiveTenantId;
    private Date loginTime;

    private String deviceId;
    private String deviceName;
    private String ip;
    private String region;
}
