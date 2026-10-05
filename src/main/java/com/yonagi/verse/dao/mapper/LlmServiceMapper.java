package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dto.resp.LlmServiceListRespDTO;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * LLM 服务 Mapper
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Mapper
public interface LlmServiceMapper extends BaseMapper<LlmServiceDO> {

    /** 模型调用实时复核资源及提供者状态，缓存只能提供配置。 */
    @Select("SELECT COUNT(*) FROM t_llm_service s JOIN t_user u ON u.user_id=s.created_by "
            + "JOIN t_tenant t ON t.tenant_id=s.tenant_id "
            + "WHERE s.service_id=#{serviceId} AND s.tenant_id=#{tenantId} AND s.status=1 AND s.del_flag=0 "
            + "AND u.status=1 AND u.del_flag=0 AND t.status=1 AND t.del_flag=0")
    int countCallableService(@Param("tenantId") Long tenantId, @Param("serviceId") Long serviceId);

    // 删除名称改为墓碑名，避免与同租户同名历史删除行的唯一约束冲突。
    @Update("UPDATE t_llm_service SET status=0,del_flag=1,api_key='',provider_settings=NULL,name=CONCAT('closed-',REPLACE(UUID(),'-','')) "
            + "WHERE created_by=#{userId} AND (status<>0 OR del_flag<>1 OR api_key<>'' OR provider_settings IS NOT NULL) "
            + "AND EXISTS (SELECT 1 FROM t_user WHERE user_id=#{userId} AND status=2 AND del_flag=1)")
    int deleteClosedUsersServices(@Param("userId") Long userId);

    @Select("SELECT tl.service_id, tl.name, tl.model_name, tl.provider, tl.description, tl.status, tl.context_window, tl.max_output_tokens, " +
            "COALESCE(tp.billing_mode, 'UNPRICED') AS billing_status, tp.currency, tu.username " +
            "FROM t_llm_service tl " +
            "JOIN t_user tu ON tl.created_by = tu.user_id " +
            "LEFT JOIN t_llm_service_pricing tp ON tl.active_pricing_id = tp.pricing_id " +
            "WHERE tl.tenant_id = #{tenantId} AND tl.del_flag = 0 " +
            "ORDER BY tl.create_time DESC")
    @Results({
            @Result(property = "serviceId", column = "service_id"),
            @Result(property = "modelName", column = "model_name"),
            @Result(property = "contextWindow", column = "context_window"),
            @Result(property = "maxOutputTokens", column = "max_output_tokens"),
            @Result(property = "billingStatus", column = "billing_status"),
            @Result(property = "createdByUsername", column = "username")
    })
    List<LlmServiceListRespDTO.LlmServiceInfo> selectByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 锁定指定模型服务，串行化同一服务的计费版本替换。
     */
    @Select("SELECT service_id FROM t_llm_service " +
            "WHERE tenant_id = #{tenantId} AND service_id = #{serviceId} AND del_flag = 0 FOR UPDATE")
    Long lockByServiceId(@Param("tenantId") Long tenantId, @Param("serviceId") Long serviceId);
}
