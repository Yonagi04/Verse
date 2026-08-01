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
 * @date 2026/08/01 11:10
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class TenantMembersListRespDTO {

    private List<TenantMemberInfo> tenantMembers;

    private Long total;

    private Long totalPages;

    private Integer page;

    private Integer pageSize;

    @Data
    @Accessors(chain = true)
    public static class TenantMemberInfo {

        @JsonSerialize(using = ToStringSerializer.class)
        private Long userId;

        private String username;

        private String nickname;

        private String role;

        private Date joinedAt;
    }
}
