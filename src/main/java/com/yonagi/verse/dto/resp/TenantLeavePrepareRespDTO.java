package com.yonagi.verse.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/02 13:25
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TenantLeavePrepareRespDTO {

    private String warningDescription;

    private List<String> warningTips;
}
