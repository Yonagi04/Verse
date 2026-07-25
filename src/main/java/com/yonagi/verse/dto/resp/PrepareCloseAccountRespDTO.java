package com.yonagi.verse.dto.resp;

import lombok.Data;

import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/25 19:44
 */
@Data
public class PrepareCloseAccountRespDTO {

    private String warningDescription;

    private List<String> warningTips;
}
