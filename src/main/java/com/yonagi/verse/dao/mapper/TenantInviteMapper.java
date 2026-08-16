package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yonagi.verse.dao.entity.TenantInviteDO;
import com.yonagi.verse.dto.resp.TenantInviteListRespDTO;
import org.apache.ibatis.annotations.*;

import java.util.Date;

/**
 * 租户邀请 Mapper
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Mapper
public interface TenantInviteMapper extends BaseMapper<TenantInviteDO> {

    /**
     * 分页查询租户邀请列表（根据用户ID和租户ID）
     * @param objectPage 页码对象
     * @param tenantId 租户ID
     * @param now 当前时间
     * @return 分页结果
     */
    @Select("SELECT ti.id, ti.code, ti.created_by, tu.username, ti.usage_count, ti.is_active, ti.expires_at, ti.create_time " +
            "FROM t_tenant_invite ti " +
            "JOIN t_user tu ON ti.created_by = tu.user_id " +
            "WHERE ti.tenant_id = #{tenantId} " +
            "AND (ti.expires_at IS NULL OR ti.expires_at > #{now}) " +
            "ORDER BY ti.create_time DESC")
    @Results({
            @Result(property = "createdBy", column = "created_by"),
            @Result(property = "usageCount", column = "usage_count"),
            @Result(property = "isActive", column = "is_active"),
            @Result(property = "expiresAt", column = "expires_at"),
            @Result(property = "createTime", column = "create_time"),
            @Result(property = "createdByUsername", column = "username")
    })
    Page<TenantInviteListRespDTO.TenantInviteInfo> selectPageByTenantId(Page<?> objectPage, @Param("tenantId") Long tenantId, @Param("now") Date now);

    /**
     * usage_count 原子 +1，不依赖读取旧值，避免读改写竞态。
     */
    @Update("UPDATE t_tenant_invite SET usage_count = usage_count + 1 WHERE id = #{inviteId}")
    int incrementUsageCount(@Param("inviteId") Long inviteId);
}
