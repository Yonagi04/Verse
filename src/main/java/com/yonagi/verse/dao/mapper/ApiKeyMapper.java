package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * API Key Mapper
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKeyDO> {

    /** 仅在本次使用时间更新时回写，避免异步任务乱序使最近使用时间倒退。 */
    @Update("UPDATE t_api_key SET last_used_at = #{usedAt} "
            + "WHERE api_key_id = #{apiKeyId} "
            + "AND (last_used_at IS NULL OR last_used_at < #{usedAt})")
    int updateLastUsedAtIfLater(@Param("apiKeyId") Long apiKeyId, @Param("usedAt") Date usedAt);
}
