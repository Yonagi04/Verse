package com.yonagi.verse.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** 注销场景的有界统计清理，仅接受已由用例校验的用户标识。 */
@Mapper
public interface UsageCleanupMapper {
    @Select("SELECT id FROM t_token_usage WHERE user_id=#{userId} ORDER BY id LIMIT #{limit}")
    List<Long> selectUsageIds(@Param("userId") Long userId, @Param("limit") int limit);

    @Delete("""
            <script>DELETE FROM t_token_usage_cost WHERE usage_id IN
            <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>
            """)
    int deleteCosts(@Param("ids") List<Long> ids);

    @Delete("""
            <script>DELETE FROM t_token_usage WHERE user_id=#{userId} AND id IN
            <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>
            """)
    int deleteUsage(@Param("userId") Long userId, @Param("ids") List<Long> ids);

    @Delete("DELETE FROM t_token_usage_hourly_agg WHERE user_id=#{userId} LIMIT #{limit}")
    int deleteHourlyBatch(@Param("userId") Long userId, @Param("limit") int limit);

    @Delete("DELETE FROM t_token_usage_outbox WHERE user_id=#{userId} LIMIT #{limit}")
    int deleteOutboxBatch(@Param("userId") Long userId, @Param("limit") int limit);
}
