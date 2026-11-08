package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.TokenUsageCostDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Token 用量费用快照 Mapper。 */
@Mapper
public interface TokenUsageCostMapper extends BaseMapper<TokenUsageCostDO> {
    /** 查询指定用量事实的费用行数。 */
    @Select("SELECT COUNT(*) FROM t_token_usage_cost WHERE usage_id=#{usageId}")
    long countByUsageId(@Param("usageId") Long usageId);

}
