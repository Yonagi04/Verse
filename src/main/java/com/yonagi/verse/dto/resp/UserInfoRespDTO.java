package com.yonagi.verse.dto.resp;

import lombok.Data;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/19 18:12
 */
@Data
public class UserInfoRespDTO {

    private Long userId;

    private String username;

    private String nickname;
}
