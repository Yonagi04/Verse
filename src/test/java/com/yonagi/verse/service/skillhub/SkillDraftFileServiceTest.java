package com.yonagi.verse.service.skillhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDraftDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillDraftFileServiceTest {
    private final SkillAccessPolicy access = mock(SkillAccessPolicy.class);
    private final SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
    private final SkillGithubSnapshotCodec codec = new SkillGithubSnapshotCodec(new ObjectMapper());
    private final SkillDraftFileService service = new SkillDraftFileService(access, artifacts, codec);

    @Test
    void onlyCurrentOwnedReadyArtifactIsListedWithStablePaginationAndSnapshotIdentity() {
        SkillDraftDO draft = SkillTestFixture.draft(); draft.setStatus("READY"); draft.setArtifactId(40L);
        when(access.readDraft(1L, 30L)).thenReturn(new SkillOwnedDraft(SkillTestFixture.publisher(), SkillTestFixture.skill(), draft));
        SkillArtifactDO artifact = SkillTestFixture.artifact();
        List<SkillValidatedFile> files = new ArrayList<>();
        for (int index = 59; index >= 0; index--) files.add(new SkillValidatedFile("a%02d.txt".formatted(index), "a".repeat(64), index));
        artifact.setManifestJson(codec.encode(new SkillPackageManifest("one", "说明", files), 256 * 1024));
        when(artifacts.selectById(40L)).thenReturn(artifact);
        var page = service.files(1L, 30L, 2, 50);
        assertEquals(60, page.total()); assertEquals(10, page.items().size());
        assertEquals("a50.txt", page.items().getFirst().relativePath());
        assertEquals("40", page.artifactId()); assertEquals(artifact.getContentSha256(), page.contentSha256());
        artifact.setOwnerUserId(2L);
        assertThrows(ClientException.class, () -> service.files(1L, 30L, 1, 50));
        artifact.setOwnerUserId(1L); draft.setStatus("EDITING");
        assertThrows(ClientException.class, () -> service.files(1L, 30L, 1, 50));
        assertThrows(ClientException.class, () -> service.files(1L, 30L, 1, 500));
    }
}
