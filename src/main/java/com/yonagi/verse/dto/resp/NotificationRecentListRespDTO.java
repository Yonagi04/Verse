package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.Date;
import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/09/27 10:51
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class NotificationRecentListRespDTO {

    private List<NotificationInfo> records;

    @Data
    @Accessors(chain = true)
    public static class NotificationInfo {

        @JsonSerialize(using = ToStringSerializer.class)
        private Long notificationId;

        private String title;

        private String content;

        private String severity;

        private Date createTime;
    }
}
