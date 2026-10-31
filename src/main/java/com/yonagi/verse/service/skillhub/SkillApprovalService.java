package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillApprovalStatusEnum;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.skillhub.SkillApprovalDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillApprovalMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 当前明确的LOCAL_AUTO审批，不实现人工角色或外部工作流。 */
@Service
@RequiredArgsConstructor
public class SkillApprovalService {
    private static final String DECISION_SOURCE = "LOCAL_AUTO";
    private static final String POLICY_VERSION = "AUTO_PASS_V1";
    private static final String ACTOR_TYPE = "SYSTEM";
    private static final String REASON_CODE = "PRE_INTEGRATION_AUTO_APPROVAL";
    private final SkillApprovalMapper approvalMapper;

    /**
     * @param submissionId 已冻结提交的标识
     * @param snapshotHash 制品与声明共同确定的快照摘要
     * @return 原事务内先建PENDING单据、再自动APPROVED的决定事实
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public SkillApprovalDO createAndApprove(Long submissionId, String snapshotHash) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SkillApprovalDO approval = new SkillApprovalDO();
        approval.setApprovalId(SnowflakeIdUtil.nextId());
        approval.setSubmissionId(submissionId);
        approval.setStatus(SkillApprovalStatusEnum.PENDING.name());
        approval.setDecisionSource(DECISION_SOURCE);
        approval.setPolicyVersion(POLICY_VERSION);
        approval.setActorType(ACTOR_TYPE);
        approval.setReasonCode(REASON_CODE);
        approval.setRequestSnapshotHash(snapshotHash);
        approval.setCreateTime(now);
        approval.setUpdateTime(now);
        approvalMapper.insert(approval);
        int approved = approvalMapper.update(null, Wrappers.lambdaUpdate(SkillApprovalDO.class)
                .eq(SkillApprovalDO::getApprovalId, approval.getApprovalId())
                .eq(SkillApprovalDO::getStatus, SkillApprovalStatusEnum.PENDING.name())
                .eq(SkillApprovalDO::getRequestSnapshotHash, snapshotHash)
                .set(SkillApprovalDO::getStatus, SkillApprovalStatusEnum.APPROVED.name())
                .set(SkillApprovalDO::getDecidedAt, now).set(SkillApprovalDO::getUpdateTime, now));
        if (approved != 1) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        approval.setStatus(SkillApprovalStatusEnum.APPROVED.name());
        approval.setDecidedAt(now);
        return approval;
    }
}
