package com.yonagi.verse.dto.resp;

import lombok.Data;

import java.util.Date;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/26 13:28
 */
@Data
public class TenantInviteRespDTO {

    private String inviteCode;

    private Date expiresAt;
}
