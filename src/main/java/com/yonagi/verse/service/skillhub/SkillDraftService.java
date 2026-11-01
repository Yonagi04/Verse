package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.enums.SkillImportTaskStatusEnum;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillArtifactStatusEnum;
import com.yonagi.verse.common.enums.SkillDraftStatusEnum;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.enums.SkillStatusEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillPublisherDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDraftMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dto.req.SkillDraftCreateReqDTO;
import com.yonagi.verse.dto.req.SkillDraftSaveReqDTO;
import com.yonagi.verse.dto.resp.SkillDraftRespDTO;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

/** 首版草稿编辑与恢复；所有修改都不触碰公开版本指针。 */
@Service
@RequiredArgsConstructor
public class SkillDraftService {
    private static final String CREATE_COMMAND = "CREATE_DRAFT";
    private final SkillAccessPolicy accessPolicy;
    private final SkillPublisherService publisherService;
    private final SkillDraftMapper draftMapper;
    private final SkillMapper skillMapper;
    private final SkillImportTaskMapper taskMapper;
    private final SkillArtifactMapper artifactMapper;
    private final SkillIdempotencyService idempotencyService;
    private final SkillPublicationPolicy publicationPolicy;
    private final SkillPublicationSnapshotCodec snapshotCodec;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    /**
     * @param userId 可信账号
     * @param input 首版固定slug
     * @param key 客户端原始幂等键
     * @return 新草稿或相同命令创建的原草稿
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillDraftRespDTO create(Long userId, SkillDraftCreateReqDTO input, String key) {
        if (input == null || !validator.validate(input).isEmpty()) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        SkillPublisherDO publisher = publisherService.ensure(userId);
        Long replay = idempotencyService.replay(userId, CREATE_COMMAND, key, input, Long.class);
        if (replay != null) {
            return view(accessPolicy.readDraft(userId, replay));
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SkillDO skill = new SkillDO();
        skill.setSkillId(SnowflakeIdUtil.nextId());
        skill.setPublisherId(publisher.getPublisherId());
        skill.setSlug(input.slug());
        skill.setStatus(SkillStatusEnum.DRAFT.name());
        skill.setRevision(0L);
        skill.setCreateTime(now);
        skill.setUpdateTime(now);
        skillMapper.insert(skill);
        SkillDraftDO draft = new SkillDraftDO();
        draft.setDraftId(SnowflakeIdUtil.nextId());
        draft.setSkillId(skill.getSkillId());
        draft.setOwnerUserId(userId);
        draft.setStatus(SkillDraftStatusEnum.EDITING.name());
        draft.setRevision(0L);
        draft.setCreateTime(now);
        draft.setUpdateTime(now);
        draftMapper.insert(draft);
        idempotencyService.record(userId, CREATE_COMMAND, key, input, draft.getDraftId(), draft.getDraftId());
        return view(new SkillOwnedDraft(publisher, skill, draft));
    }

    /**
     * @param userId 可信账号
     * @param draftId 本人草稿
     * @return 服务端保存的真实表单、修订与检查预览
     */
    public SkillDraftRespDTO get(Long userId, Long draftId) {
        return view(accessPolicy.readDraft(userId, draftId));
    }

    /**
     * @param userId 可信账号
     * @param draftId 本人草稿
     * @param input 带修订号的完整表单
     * @return 保存后修订；并发旧修订不能覆盖新内容
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillDraftRespDTO save(Long userId, Long draftId, SkillDraftSaveReqDTO input) {
        if (input == null || !validator.validate(input).isEmpty()) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        publicationPolicy.validateDraft(input.metadata());
        SkillOwnedDraft owned = accessPolicy.lockDraft(userId, draftId);
        SkillDraftDO draft = owned.draft();
        requireEditable(draft, input.revision());
        if ((Boolean.TRUE.equals(input.metadata().distributionAuthorized())
                || Boolean.TRUE.equals(input.metadata().declarationsConfirmed()))
                && (!SkillDraftStatusEnum.READY.name().equals(draft.getStatus()) || draft.getArtifactId() == null)) {
            throw new ClientException("请在检查完成后确认当前内容的声明", SkillErrorCodeEnum.INVALID_INPUT);
        }
        String json = snapshotCodec.encode(input.metadata());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int changed = draftMapper.update(null, Wrappers.lambdaUpdate(SkillDraftDO.class)
                .eq(SkillDraftDO::getDraftId, draftId).eq(SkillDraftDO::getOwnerUserId, userId)
                .eq(SkillDraftDO::getRevision, input.revision())
                .set(SkillDraftDO::getMetadataJson, json).set(SkillDraftDO::getVersionLabel, input.metadata().version())
                .set(SkillDraftDO::getRevision, input.revision() + 1).set(SkillDraftDO::getUpdateTime, now));
        if (changed != 1) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        draft.setMetadataJson(json);
        draft.setVersionLabel(input.metadata().version());
        draft.setRevision(input.revision() + 1);
        draft.setUpdateTime(now);
        return view(owned);
    }

    /**
     * @param userId 可信账号
     * @param draftId 本人未提交草稿
     * @param revision 当前草稿修订号
     */
    @Transactional(rollbackFor = Exception.class)
    public void abandon(Long userId, Long draftId, Long revision) {
        SkillOwnedDraft owned = accessPolicy.lockDraft(userId, draftId);
        SkillDraftDO draft = owned.draft();
        if (SkillDraftStatusEnum.CANCELLED.name().equals(draft.getStatus())) {
            return;
        }
        requireEditable(draft, revision);
        draft.setStatus(SkillDraftStatusEnum.CANCELLED.name());
        draft.setRevision(draft.getRevision() + 1);
        draft.setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
        draftMapper.updateById(draft);
        taskMapper.update(null, Wrappers.lambdaUpdate(SkillImportTaskDO.class)
                .eq(SkillImportTaskDO::getDraftId, draftId).eq(SkillImportTaskDO::getOwnerUserId, userId)
                .notIn(SkillImportTaskDO::getStatus, SkillImportTaskStatusEnum.SUCCEEDED.name(),
                        SkillImportTaskStatusEnum.FAILED.name(), SkillImportTaskStatusEnum.CANCELLED.name())
                .set(SkillImportTaskDO::getStatus, SkillImportTaskStatusEnum.CANCELLED.name())
                .set(SkillImportTaskDO::getUpdateTime, draft.getUpdateTime()));
    }

    private void requireEditable(SkillDraftDO draft, Long revision) {
        Set<String> editable = Set.of(SkillDraftStatusEnum.EDITING.name(), SkillDraftStatusEnum.READY.name());
        if (revision == null || !revision.equals(draft.getRevision()) || !editable.contains(draft.getStatus())) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
    }

    private SkillDraftRespDTO view(SkillOwnedDraft owned) {
        SkillDraftDO draft = owned.draft();
        SkillArtifactDO artifact = draft.getArtifactId() == null ? null
                : artifactMapper.selectById(draft.getArtifactId());
        JsonNode manifest = null;
        if (artifact != null && SkillArtifactStatusEnum.READY.name().equals(artifact.getStatus())
                && draft.getOwnerUserId().equals(artifact.getOwnerUserId())) {
            try {
                manifest = objectMapper.readTree(artifact.getManifestJson());
            } catch (JsonProcessingException exception) {
                throw new ServerException("Skill检查快照不可读取", exception, SkillErrorCodeEnum.UNAVAILABLE);
            }
        } else {
            artifact = null;
        }
        return new SkillDraftRespDTO(draft.getDraftId().toString(), draft.getSkillId().toString(),
                owned.skill().getSlug(), draft.getStatus(), draft.getRevision(),
                draft.getLatestJobId() == null ? null : draft.getLatestJobId().toString(),
                draft.getMetadataJson() == null ? null : snapshotCodec.decode(draft.getMetadataJson()),
                manifest == null ? null : manifest.path("packageName").textValue(),
                manifest == null ? null : manifest.path("packageDescription").textValue(),
                artifact == null ? null : artifact.getSkillMarkdown(),
                artifact == null ? null : artifact.getArchiveBytes(),
                artifact == null ? null : artifact.getFileCount(), draft.getUpdateTime().atOffset(ZoneOffset.UTC),
                artifact == null ? null : artifact.getArtifactId().toString(),
                artifact == null ? null : artifact.getContentSha256(), artifact == null ? null
                        : new SkillGithubSnapshotCodec(objectMapper).source(artifact.getSourceType(), artifact.getSourceJson()));
    }
}
