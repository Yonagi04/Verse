package com.yonagi.verse.service;

import com.yonagi.verse.dto.req.TenantJoinRejectReqDTO;
import com.yonagi.verse.dto.resp.TenantJoinReqListRespDTO;

/**
 * 加入审批流服务接口。
 */
public interface TenantApprovalService {

    TenantJoinReqListRespDTO listJoinRequests(Long userId, Long tenantId, Integer pageNum, Integer pageSize);

    /** 批准指定租户的待审批申请；跨租户申请按不存在处理，状态更新受相同租户约束。 */
    Boolean approveJoinRequest(Long userId, Long tenantId, Long requestId);

    /** 拒绝指定租户的待审批申请；跨租户申请按不存在处理，状态更新受相同租户约束。 */
    Boolean rejectJoinRequest(Long userId, Long tenantId, Long requestId, TenantJoinRejectReqDTO requestParam);

    Long getUnreviewedJoinReqCount(Long userId, Long tenantId);

    /** 创建加入申请（审批模式下由加入流程调用） */
    void createJoinRequest(Long userId, Long tenantId, Long inviteId, String tenantName);
}
