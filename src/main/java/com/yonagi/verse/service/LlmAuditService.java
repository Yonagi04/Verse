package com.yonagi.verse.service;

import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dto.resp.LlmAuditDetailRespDTO;
import com.yonagi.verse.dto.resp.LlmAuditListRespDTO;

/**
 * LLM 调用审计查询服务 — 按角色隔离：MEMBER 只能查自己，ADMIN/SUPER_ADMIN 可查全员并按用户筛选。
 *
 * @author Yonagi
 */
public interface LlmAuditService {

    LlmAuditListRespDTO listAudit(UserContext ctx, Long tenantId, Integer pageNum, Integer pageSize, Long userId);

    LlmAuditDetailRespDTO getAuditDetail(UserContext ctx, Long tenantId, Long auditId);
}
