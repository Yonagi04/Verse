package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yonagi.verse.dao.entity.TenantJoinRequestDO;
import com.yonagi.verse.dto.resp.TenantJoinReqListRespDTO;
import org.apache.ibatis.annotations.*;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/02 19:14
 */
@Mapper
public interface TenantJoinRequestMapper extends BaseMapper<TenantJoinRequestDO> {

    /**
     * 分页查询租户加入请求列表（根据租户ID），并关联申请人用户名，根据申请时间降序排序
     * @param objectPage 分页对象
     * @param tenantId 租户ID
     * @return 分页结果
     */
    @Select("SELECT tjr.request_id, tjr.user_id, tu.username, ti.code AS invite_code, tjr.status, reviewer.username AS reviewed_by, tjr.review_comment, tjr.requested_at, tjr.reviewed_at " +
            "FROM t_tenant_join_request tjr " +
            "JOIN t_user tu ON tjr.user_id = tu.user_id " +
            "LEFT JOIN t_tenant_invite ti ON tjr.invite_id = ti.id " +
            "LEFT JOIN t_user reviewer ON tjr.reviewed_by = reviewer.user_id " +
            "WHERE tjr.tenant_id = #{tenantId} " +
            "ORDER BY tjr.requested_at DESC")
    @Results({
            @Result(property = "requestId", column = "request_id"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "username", column = "username"),
            @Result(property = "inviteCode", column = "invite_code"),
            @Result(property = "status", column = "status"),
            @Result(property = "reviewedBy", column = "reviewed_by"),
            @Result(property = "reviewComment", column = "review_comment"),
            @Result(property = "requestedAt", column = "requested_at"),
            @Result(property = "reviewedAt", column = "reviewed_at")
    })
    Page<TenantJoinReqListRespDTO.TenantJoinReqInfo> selectPageByTenantId(Page<Object> objectPage, Long tenantId);
}
