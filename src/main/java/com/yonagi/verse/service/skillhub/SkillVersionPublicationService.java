package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillApprovalDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillApprovalMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import com.yonagi.verse.dto.req.SkillVersionPublishReqDTO;
import com.yonagi.verse.dto.resp.SkillPublicationResultRespDTO;
import com.yonagi.verse.dto.resp.SkillVersionReceiptRespDTO;

import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;

/** 发布命令先恢复长期成功事实，再验证编辑区和确认意图。 */
@Service
@RequiredArgsConstructor
public class SkillVersionPublicationService {
    private static final String COMMAND = "PUBLISH_SKILL_VERSION_V1";
    private final SkillAccessPolicy access;
    private final SkillVersionWorkspaceService workspace;
    private final SkillVersionPublicationPolicy policy;
    private final SkillVersionWriter writer;
    private final SkillVersionMapper versions;
    private final SkillApprovalMapper approvals;
    private final SkillGithubSnapshotCodec json;
    private final SkillIdempotencyService commands;
    private final Validator validator;

    /**
     * @return 一次成功只追加一个节点，所有事实随同一事务提交
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillVersionReceiptRespDTO publish(
            Long userId, Long skillId, SkillVersionPublishReqDTO input, String key) {
        if (input == null || !validator.validate(input).isEmpty()) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        String normalized = SkillIdempotencyService.normalizeKey(key);
        access.lockSkill(userId, skillId);
        List<Object> payload =
                List.of(
                        COMMAND,
                        skillId.toString(),
                        input.workspaceRevision().toString(),
                        input.expectedSkillRevision().toString(),
                        input.type(),
                        input.confirmationDigest());
        String hash = DigestUtil.sha256Hex(json.encode(payload, 8192));
        SkillVersionDO replay = find(userId, normalized, true);
        if (replay != null) {
            if (!skillId.equals(replay.getSkillId())
                    || !hash.equals(replay.getPublishRequestHash())) {
                throw new ClientException(SkillErrorCodeEnum.IDEMPOTENCY_CONFLICT);
            }
            return receipt(replay);
        }
        SkillOwnedDraft reference = workspace.read(userId, skillId);
        SkillOwnedDraft owned = access.lockDraft(userId, reference.draft().getDraftId());
        if (!input.workspaceRevision().equals(owned.draft().getRevision())
                || !input.expectedSkillRevision().equals(owned.skill().getRevision())) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        SkillVersionPublicationPolicy.Plan plan = policy.plan(owned, input.type(), true);
        if (!input.confirmationDigest().equals(plan.digest())) {
            throw new ClientException("内容或轮次已变化，请刷新并重新确认", SkillErrorCodeEnum.CONFLICT);
        }
        SkillVersionReceiptRespDTO result = writer.publish(owned, plan, normalized, hash);
        commands.record(
                userId, COMMAND, normalized, payload, Long.valueOf(result.versionId()), result);
        return result;
    }

    /**
     * @return 查询长期回执，未找到不能宣称原事务失败
     */
    public SkillPublicationResultRespDTO result(Long userId, Long skillId, String key) {
        access.readSkill(userId, skillId);
        SkillVersionDO version = find(userId, SkillIdempotencyService.normalizeKey(key), false);
        if (version != null && !skillId.equals(version.getSkillId())) {
            throw new ClientException(SkillErrorCodeEnum.IDEMPOTENCY_CONFLICT);
        }
        return new SkillPublicationResultRespDTO(
                version == null ? "UNCONFIRMED" : "COMMITTED",
                version == null ? null : receipt(version));
    }

    private SkillVersionDO find(Long userId, String key, boolean lock) {
        return versions.selectOne(
                Wrappers.lambdaQuery(SkillVersionDO.class)
                        .eq(SkillVersionDO::getPublishedBy, userId)
                        .eq(SkillVersionDO::getPublishKey, key)
                        .last(lock ? "FOR UPDATE" : ""));
    }

    private SkillVersionReceiptRespDTO receipt(SkillVersionDO version) {
        SkillApprovalDO approval =
                version.getApprovedSubmissionId() == null
                        ? null
                        : approvals.selectOne(
                                Wrappers.lambdaQuery(SkillApprovalDO.class)
                                        .eq(
                                                SkillApprovalDO::getSubmissionId,
                                                version.getApprovedSubmissionId()));
        if ("RELEASE".equals(version.getVersionType()) && approval == null) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        return new SkillVersionReceiptRespDTO(
                version.getSkillId().toString(),
                version.getVersionId().toString(),
                version.getVersionType(),
                SkillVersionRules.label(version),
                version.getSequenceNumber().toString(),
                version.getPublishedAt().atOffset(ZoneOffset.UTC),
                string(version.getSourceDraftVersionId()),
                string(version.getApprovedSubmissionId()),
                approval == null ? null : string(approval.getApprovalId()));
    }

    private String string(Long value) {
        return value == null ? null : value.toString();
    }

}
