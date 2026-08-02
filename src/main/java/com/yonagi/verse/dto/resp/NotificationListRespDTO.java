package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;
import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/01 21:36
 */
@Data
@Accessors(chain = true)
public class NotificationListRespDTO {

    private Integer total;

    private List<NotificationInfo> records;

    @Data
    @Accessors(chain = true)
    public static class NotificationInfo {

        @JsonSerialize(using = ToStringSerializer.class)
        private Long notificationId;

        private String title;

        private String content;

        private String type;

        private String severity;

        private Boolean isRead;

        private Date createTime;
    }
}
