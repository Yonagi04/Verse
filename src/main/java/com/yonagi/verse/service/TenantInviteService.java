package com.yonagi.verse.service;

import com.yonagi.verse.dao.entity.TenantInviteDO;
import com.yonagi.verse.dto.req.TenantInviteReqDTO;
import com.yonagi.verse.dto.resp.TenantInviteRespDTO;
import com.yonagi.verse.dto.resp.TenantInviteListRespDTO;
import com.yonagi.verse.dto.resp.TenantJoinInfoRespDTO;

/**
 * 邀请码管理服务接口。
 */
public interface TenantInviteService {

    TenantInviteRespDTO inviteUser(Long userId, Long tenantId, TenantInviteReqDTO requestParam);

    TenantJoinInfoRespDTO getTenantAndInviteCodeInfo(String inviteCode);

    TenantInviteListRespDTO listTenantInviteCodes(Long userId, Long tenantId, Integer pageNum, Integer pageSize);

    Boolean deactivateInviteCode(Long userId, Long tenantId, Long inviteCodeId);

    Boolean activateInviteCode(Long userId, Long tenantId, Long inviteCodeId);

    /** 校验并获取邀请码信息，供加入流程调用 */
    TenantInviteDO validateAndGetInviteCode(String code);

    /** 增加邀请码使用次数，供加入/审批通过后调用 */
    void incrementUsageCount(Long inviteId);
}
