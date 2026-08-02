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
 * @date 2026/08/02 19:21
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class TenantInviteListRespDTO {

    private List<TenantInviteInfo> inviteCodes;

    private Long total;

    private Long totalPages;

    private Integer page;

    private Integer pageSize;

    @Data
    @Accessors(chain = true)
    public static class TenantInviteInfo {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;

        private String code;

        private String inviteUrl;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long createdBy;

        private String createdByUsername;

        private Integer usageCount;

        private Integer isActive;

        private Date expiresAt;

        private Date createTime;
    }
}
