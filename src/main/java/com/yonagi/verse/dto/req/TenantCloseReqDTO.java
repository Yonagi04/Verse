package com.yonagi.verse.dto.req;

import lombok.Data;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/01 10:15
 */
@Data
public class TenantCloseReqDTO {

    private String disableToken;

    private String confirmText;
}
