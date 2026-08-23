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

    @Select("SELECT tl.service_id, tl.name, tl.provider, tl.status, tu.username " +
            "FROM t_llm_service tl " +
            "JOIN t_user tu ON tl.created_by = tu.user_id " +
            "WHERE tl.tenant_id = #{tenantId} AND tl.del_flag = 0 " +
            "ORDER BY tl.create_time DESC")
    @Results({
            @Result(property = "serviceId", column = "service_id"),
            @Result(property = "createdByUsername", column = "username")
    })
    List<LlmServiceListRespDTO.LlmServiceInfo> selectByTenantId(@Param("tenantId") Long tenantId);
}
