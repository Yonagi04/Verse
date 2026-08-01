package com.yonagi.verse.dto.resp;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/01 09:23
 */
@Data
public class TenantClosePrepareRespDTO {

    private String disableToken;

    private Date tokenExpireTime;

    private String warningDescription;

    private List<String> warningTips;
}
