package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/06 19:57
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TenantLeaveRespDTO {

    /** 退出后的个人租户 ID，按字符串输出避免 JavaScript 精度损失。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long targetTenantId;
}
