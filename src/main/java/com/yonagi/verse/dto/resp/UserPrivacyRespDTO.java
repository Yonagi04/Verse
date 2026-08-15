package com.yonagi.verse.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/15 10:46
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserPrivacyRespDTO {

    private Boolean showBio;

    private Boolean showRegion;

    private Boolean showTimezone;
}
