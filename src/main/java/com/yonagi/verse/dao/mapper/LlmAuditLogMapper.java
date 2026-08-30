package com.yonagi.verse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yonagi.verse.dao.entity.LlmAuditLogDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * LLM 调用审计索引 Mapper
 *
 * @author Yonagi
 */
@Mapper
public interface LlmAuditLogMapper extends BaseMapper<LlmAuditLogDO> {
}
