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
 * @date 2026/08/05 18:55
 */
@Data
@Accessors(chain = true)
public class TenantJoinReqListRespDTO {

    private List<TenantJoinReqInfo> requestList;

    private Long total;

    private Long totalPages;

    private Integer page;

    private Integer pageSize;

    @Data
    @Accessors(chain = true)
    public static class TenantJoinReqInfo {

        @JsonSerialize(using = ToStringSerializer.class)
        private Long requestId;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long userId;

        private String username;

        private String inviteCode;

        private String status;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long reviewedBy;

        private String reviewComment;

        private Date requestedAt;

        private Date reviewedAt;
    }
}
