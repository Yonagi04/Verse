package com.yonagi.verse.dto.req;

import lombok.Data;

import java.util.Date;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/26 13:46
 */
@Data
public class TenantInviteReqDTO {

    // 邀请码过期时间，如果为空表示永久有效
    private Date expireAt;
}
