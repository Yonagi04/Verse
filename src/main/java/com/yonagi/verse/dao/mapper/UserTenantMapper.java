package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.UserTenantDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户-租户关联 Mapper
 *
 * @author Yonagi
 * @date 2026/07/11
 */
@Mapper
public interface UserTenantMapper extends BaseMapper<UserTenantDO> {
}
