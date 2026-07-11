package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * API Key Mapper
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKeyDO> {
}
