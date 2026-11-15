package com.yonagi.verse.service.skillhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class SkillVersionQueryServiceTest {
    private final SkillAccessPolicy access = mock(SkillAccessPolicy.class);
    private final SkillVersionMapper versions = mock(SkillVersionMapper.class);
    private final SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
    private final SkillPublicationSnapshotCodec snapshots =
            new SkillPublicationSnapshotCodec(new ObjectMapper());
    private final SkillVersionQueryService service =
            new SkillVersionQueryService(
                    access,
                    versions,
                    artifacts,
                    new SkillGithubSnapshotCodec(new ObjectMapper()),
                    snapshots);
    private SkillOwnedResource resource;
    private SkillVersionDO node;
    private final AtomicReference<LambdaQueryWrapper<SkillVersionDO>> query =
            new AtomicReference<>();

    @BeforeEach
    void setup() {
        SkillTestFixture.initializeMapping(SkillVersionDO.class, SkillArtifactDO.class);
        resource = new SkillOwnedResource(SkillTestFixture.publisher(), SkillTestFixture.skill());
        resource.skill().setStatus("ACTIVE");
        resource.skill().setCurrentVersionId(70L);
        resource.skill().setVersionSequence(4L);
        when(access.readSkill(1L, 20L)).thenReturn(resource);
        node = new SkillVersionDO();
        node.setVersionId(71L);
        node.setSkillId(20L);
        node.setVersionType("DRAFT");
        node.setReleaseNumber(2);
        node.setDraftNumber(1);
        node.setSequenceNumber(4L);
        node.setArtifactId(40L);
        node.setPublishedAt(SkillTestFixture.now());
        node.setStatus("AVAILABLE");
        node.setReleaseNotes("私人说明");
        node.setDeclarationsJson(snapshots.encode(SkillTestFixture.metadata()));
        node.setAddedCount(1);
        node.setModifiedCount(0);
        node.setDeletedCount(0);
        when(versions.selectById(71L)).thenAnswer(call -> node);
        when(versions.selectCount(any()))
                .thenAnswer(
                        call -> {
                            query.set(call.getArgument(0));
                            return 3L;
                        });
        when(versions.selectList(any()))
                .thenAnswer(
                        call -> {
                            LambdaQueryWrapper<SkillVersionDO> wrapper = call.getArgument(0);
                            return wrapper.getSqlSelect().contains("declarations_json")
                                            || wrapper.getSqlSelect().contains("version_label")
                                    ? List.of(node)
                                    : List.of();
                        });
        var artifact = SkillTestFixture.artifact();
        artifact.setSourceType("ZIP");
        when(artifacts.selectList(any())).thenReturn(List.of(artifact));
    }

    @Test
    void fixedHeadPaginationPreservesPrivateNodesAndDoesNotFetchDeclarationsInTimeline() {
        var page = service.timeline(1L, 20L, 1, 20, 3L);
        assertEquals("3", page.headSequence());
        assertEquals(3L, page.total());
        assertEquals(1, page.items().size());
        assertEquals("v2-draft.1", page.items().getFirst().version());
        assertNull(page.items().getFirst().metadata());
        assertTrue(query.get().getSqlSegment().contains("sequence_number <="));
        assertTrue(query.get().getParamNameValuePairs().containsValue(3L));
        assertThrows(ClientException.class, () -> service.timeline(1L, 20L, 1, 20, 5L));
        assertThrows(ClientException.class, () -> service.timeline(1L, 20L, 0, 20, null));
    }

    @Test
    void detailNormalizesHistoricalLabelAndMissingArtifactKeepsNodeFact() {
        node.setVersionLabel("legacy-semver");
        when(artifacts.selectList(any())).thenReturn(List.of());
        var detail = service.detail(1L, 20L, 71L);
        assertEquals("v2-draft.1", detail.version());
        assertEquals("v2-draft.1", detail.metadata().version());
        assertEquals("私人说明", detail.releaseNotes());
        assertFalse(detail.filesAvailable());
        assertNull(detail.source());
    }

    @Test
    void otherAccountCrossSkillAndPlatformDisabledAreDeniedBeforeReadingContent() {
        when(access.readSkill(2L, 20L))
                .thenThrow(new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE));
        assertThrows(ClientException.class, () -> service.timeline(2L, 20L, 1, 20, null));
        node.setSkillId(21L);
        assertThrows(ClientException.class, () -> service.detail(1L, 20L, 71L));
        resource.skill().setStatus("PLATFORM_DISABLED");
        assertThrows(ClientException.class, () -> service.timeline(1L, 20L, 1, 20, null));
        verifyNoInteractions(artifacts);
    }

    @Test
    void downlistedHistoryIsReadableButCannotOfferPromotionAndBlockedNodesCannotExposeFiles() {
        resource.skill().setStatus("OWNER_UNPUBLISHED");
        node.setStatus("BLOCKED");
        var detail = service.detail(1L, 20L, 71L);
        assertFalse(detail.canPublish());
        assertFalse(detail.filesAvailable());
        assertEquals("BLOCKED", detail.status());
        assertEquals("私人说明", detail.releaseNotes());
    }
}
