package com.yonagi.verse.dao.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.ExternalAuthFlowDO;
import org.apache.ibatis.annotations.*;
public interface ExternalAuthFlowMapper extends BaseMapper<ExternalAuthFlowDO> {
    @Select("SELECT * FROM t_external_auth_flow WHERE flow_id=#{id} FOR UPDATE")
    ExternalAuthFlowDO lock(String id);
    @Update("UPDATE t_external_auth_flow SET stage='AUTHENTICATING' WHERE flow_id=#{id} AND stage='AUTHORIZING'")
    int claim(String id);
    @Delete("DELETE FROM t_external_auth_flow WHERE create_time < DATE_SUB(UTC_TIMESTAMP(), INTERVAL 24 HOUR)")
    int cleanup();
}
