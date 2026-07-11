package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.TenantDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户 Mapper
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Mapper
public interface TenantMapper extends BaseMapper<TenantDO> {
}
