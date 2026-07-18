package com.yonagi.verse.dto.resp;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 用户登录响应
 *
 * @author Yonagi
 */
@Data
@Accessors(chain = true)
public class UserLoginRespDTO {

    private Long userId;

    private String username;

    private String nickname;

    private String token;

    private Date expiresAt;

    private TenantInfo currentTenant;

    @Data
    @Accessors(chain = true)
    public static class TenantInfo {

        private Long tenantId;

        private String name;

        private String type;

        private String role;
    }
}
