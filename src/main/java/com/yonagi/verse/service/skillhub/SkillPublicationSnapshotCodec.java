package com.yonagi.verse.service.skillhub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dto.skillhub.SkillPublicationMetadataDTO;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

/** 发布声明在草稿与版本之间保持同一固定快照契约。 */
@Component
@RequiredArgsConstructor
public class SkillPublicationSnapshotCodec {
    private final ObjectMapper objectMapper;

    /**
     * @param metadata 已校验的发布声明
     * @return 不可变提交所用的固定JSON形状
     */
    public String encode(SkillPublicationMetadataDTO metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new ServerException("Skill声明快照序列化失败", exception, SkillErrorCodeEnum.UNAVAILABLE);
        }
    }

    /**
     * @param snapshot 本域已保存的声明快照
     * @return 固定结构的声明；不静默丢弃未知字段
     */
    public SkillPublicationMetadataDTO decode(String snapshot) {
        try {
            return objectMapper.readerFor(SkillPublicationMetadataDTO.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(snapshot);
        } catch (JsonProcessingException exception) {
            throw new ServerException("Skill声明快照不可读取", exception, SkillErrorCodeEnum.UNAVAILABLE);
        }
    }

    /** @return 换包或新制品不能沿用上一份内容的授权声明 */
    public String clearConfirmations(String snapshot) {
        if (snapshot == null) {
            return null;
        }
        SkillPublicationMetadataDTO value = decode(snapshot);
        return encode(new SkillPublicationMetadataDTO(value.name(), value.summary(), value.categoryId(), value.tags(),
                value.version(), value.releaseNotes(), value.license(), value.originalAuthor(), value.sourceUrl(),
                value.compatibility(), value.dependencies(), false, false));
    }

    /** @return 新审批hash固定纳入真实来源，历史审批hash不重算 */
    public String publicationHash(SkillPublicationMetadataDTO metadata, String contentSha256,
                                   String sourceType, String sourceJson) {
        SkillSourceDTO source = new SkillGithubSnapshotCodec(objectMapper).source(sourceType, sourceJson);
        try {
            return cn.hutool.crypto.digest.DigestUtil.sha256Hex(objectMapper.writeValueAsBytes(
                    new PublicationSnapshot(2, metadata, contentSha256, source.type(), source)));
        } catch (JsonProcessingException exception) {
            throw new ServerException("Skill审批快照摘要不可生成", SkillErrorCodeEnum.UNAVAILABLE);
        }
    }

    /**
     * @return 新快照补平台编号；后续沿用声明，明确记录非再次主动确认
     */
    public SkillPublicationMetadataDTO versionMetadata(
            SkillPublicationMetadataDTO value, String label, String notes, boolean inherited) {
        return new SkillPublicationMetadataDTO(
                value.name(),
                value.summary(),
                value.categoryId(),
                value.tags(),
                label,
                notes,
                value.license(),
                value.originalAuthor(),
                value.sourceUrl(),
                value.compatibility(),
                value.dependencies(),
                inherited ? false : value.distributionAuthorized(),
                inherited ? false : value.declarationsConfirmed());
    }

    /**
     * @return V3审批摘要记录声明沿用依据，不修改历史hash
     */
    public String versionHash(
            SkillPublicationMetadataDTO value,
            String content,
            String type,
            String source,
            Long basisVersionId) {
        return new SkillGithubSnapshotCodec(objectMapper)
                .encode(
                        new VersionSnapshot(
                                3,
                                value,
                                content,
                                new SkillGithubSnapshotCodec(objectMapper).source(type, source),
                                basisVersionId,
                                basisVersionId == null ? "INITIAL_EXPLICIT" : "INHERITED"),
                        32768);
    }

    /** @return 旧首发适配的固定命令摘要，不包含重试时的可变状态 */
    public String initialCommandHash(Long draftId, Long revision) {
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(new SkillGithubSnapshotCodec(objectMapper)
                .encode(java.util.List.of("SUBMIT_DRAFT_V1", draftId.toString(), revision.toString()), 8192));
    }

    private record VersionSnapshot(
            int snapshotSchemaVersion,
            SkillPublicationMetadataDTO metadata,
            String contentSha256,
            SkillSourceDTO source,
            Long declarationBasisVersionId,
            String confirmationMode) {}

    private record PublicationSnapshot(int snapshotSchemaVersion, SkillPublicationMetadataDTO metadata,
                                        String contentSha256, String sourceType, SkillSourceDTO source) { }
}
