package com.yonagi.verse.dto.req;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/15 10:45
 */
@Data
public class UserPrivacyUpdateReqDTO {

    @NotNull(message = "必须设置是否展示个人简介")
    private Boolean showBio;

    @NotNull(message = "必须设置是否展示地区")
    private Boolean showRegion;

    @NotNull(message = "必须设置是否展示时区")
    private Boolean showTimezone;
}
