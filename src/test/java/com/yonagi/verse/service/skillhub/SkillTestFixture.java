package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO;
import com.yonagi.verse.dao.entity.skillhub.SkillPublisherDO;
import com.yonagi.verse.dto.SkillPublicationMetadataDTO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 最小业务场景夹具，不连接或模拟证明基础设施的真实语义。 */
final class SkillTestFixture {
    private SkillTestFixture() {
    }

    static void initializeMapping(Class<?>... types) {
        for (Class<?> type : types) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "unit");
            assistant.setCurrentNamespace("unit." + type.getSimpleName());
            TableInfoHelper.initTableInfo(assistant, type);
        }
    }

    static SkillPublisherDO publisher() {
        SkillPublisherDO publisher = new SkillPublisherDO();
        publisher.setPublisherId(10L);
        publisher.setUserId(1L);
        publisher.setHandle("author");
        publisher.setPublicName("作者");
        publisher.setStatus("ACTIVE");
        publisher.setRevision(0L);
        publisher.setCreateTime(now());
        publisher.setUpdateTime(now());
        return publisher;
    }

    static SkillDO skill() {
        SkillDO skill = new SkillDO();
        skill.setSkillId(20L);
        skill.setPublisherId(10L);
        skill.setSlug("test-skill");
        skill.setStatus("DRAFT");
        skill.setRevision(0L);
        skill.setCreateTime(now());
        skill.setUpdateTime(now());
        return skill;
    }

    static SkillDraftDO draft() {
        SkillDraftDO draft = new SkillDraftDO();
        draft.setDraftId(30L);
        draft.setSkillId(20L);
        draft.setOwnerUserId(1L);
        draft.setStatus("EDITING");
        draft.setRevision(2L);
        draft.setCreateTime(now());
        draft.setUpdateTime(now());
        return draft;
    }

    static SkillArtifactDO artifact() {
        SkillArtifactDO artifact = new SkillArtifactDO();
        artifact.setArtifactId(40L);
        artifact.setOwnerUserId(1L);
        artifact.setArchiveKey("skillhub/v1/artifact/50/sample.zip");
        artifact.setArchiveSha256(DigestUtil.sha256Hex(new byte[]{1, 2, 3}));
        artifact.setContentSha256("a".repeat(64));
        artifact.setArchiveBytes(3L);
        artifact.setUnpackedBytes(10L);
        artifact.setFileCount(1);
        artifact.setStatus("READY");
        artifact.setSkillMarkdown("# 测试");
        artifact.setManifestJson("{\"packageName\":\"test-skill\",\"packageDescription\":\"包描述\"}");
        artifact.setCreateTime(now());
        artifact.setUpdateTime(now());
        return artifact;
    }

    static SkillImportTaskDO task() {
        SkillImportTaskDO task = new SkillImportTaskDO();
        task.setJobId(50L);
        task.setDraftId(30L);
        task.setOwnerUserId(1L);
        task.setStatus("WAITING_UPLOAD");
        task.setAttemptCount(0);
        task.setNextRetryAt(now().minusSeconds(1));
        task.setCreateTime(now());
        task.setUpdateTime(now());
        return task;
    }

    static SkillPublicationMetadataDTO metadata() {
        return new SkillPublicationMetadataDTO("测试", "简介", "development", List.of("coding"), "1.0.0", "首版",
                "MIT", "本人", "https://example.com/source", "Agent Skills", "无", true, true);
    }

    static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
