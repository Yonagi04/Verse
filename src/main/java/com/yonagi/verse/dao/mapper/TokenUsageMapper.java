package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.TokenUsageDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Token 消耗 Mapper
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Mapper
public interface TokenUsageMapper extends BaseMapper<TokenUsageDO> {

    @Select("SELECT COUNT(*) FROM t_token_usage WHERE event_id=#{eventId}")
    long countByEventId(@Param("eventId") String eventId);
}
