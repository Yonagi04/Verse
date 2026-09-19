package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.TenantActivityLogDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 租户动态事实 Mapper。 */
public interface TenantActivityLogMapper extends BaseMapper<TenantActivityLogDO> {
    /** 按事件 ID 检查事实是否存在。 */
    @Select("SELECT COUNT(*) FROM t_tenant_activity_log WHERE event_id=#{eventId}")
    long countByEventId(@Param("eventId") String eventId);
}
