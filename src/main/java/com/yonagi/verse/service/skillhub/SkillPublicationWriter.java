package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dto.resp.SkillPublicationRespDTO;
import com.yonagi.verse.dto.resp.SkillVersionReceiptRespDTO;
import com.yonagi.verse.dto.skillhub.SkillPublicationMetadataDTO;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 旧首发接口适配同一版本写入器，不向旧接口扩展后续发版能力。 */
@Component
@RequiredArgsConstructor
public class SkillPublicationWriter {
    private final SkillVersionPublicationPolicy policy;
    private final SkillVersionWriter writer;

    /**
     * @return 保留旧回执形状，平台版本由新规则生成
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public SkillPublicationRespDTO publish(
            SkillOwnedDraft owned, SkillArtifactDO artifact, SkillPublicationMetadataDTO metadata, String key, String requestHash) {
        SkillVersionReceiptRespDTO receipt =
                writer.publish(owned, policy.plan(owned, "RELEASE", true), key, requestHash);
        return new SkillPublicationRespDTO(
                receipt.skillId(),
                receipt.submissionId(),
                receipt.approvalId(),
                receipt.versionId(),
                "APPROVED",
                "LOCAL_AUTO",
                "PUBLISHED",
                owned.publisher().getHandle(),
                owned.skill().getSlug());
    }
}
