package com.yonagi.verse.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/02 11:03
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class NotificationUnreadCountRespDTO {

    private Long count;
}
