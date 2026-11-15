package com.yonagi.verse.service.skillhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.entity.skillhub.SkillVersionDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillVersionMapper;
import com.yonagi.verse.service.skillhub.port.SkillArtifactStore;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class SkillVersionFileServiceTest {
    private final SkillVersionQueryService history = mock(SkillVersionQueryService.class);
    private final SkillVersionWorkspaceService workspace = mock(SkillVersionWorkspaceService.class);
    private final SkillVersionMapper versions = mock(SkillVersionMapper.class);
    private final SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
    private final SkillArtifactStore store = mock(SkillArtifactStore.class);
    private final SkillGithubSnapshotCodec json = new SkillGithubSnapshotCodec(new ObjectMapper());
    private final SkillVersionFileService service =
            new SkillVersionFileService(
                    history,
                    workspace,
                    versions,
                    artifacts,
                    json,
                    new SkillArtifactPreviewReader(store));
    private SkillVersionDO target;
    private SkillVersionDO base;

    @BeforeEach
    void setup() throws Exception {
        target = node(72L, 40L);
        target.setDiffBaseVersionId(70L);
        base = node(70L, 41L);
        when(history.node(1L, 20L, 72L)).thenAnswer(call -> target);
        when(history.node(1L, 20L, 70L)).thenAnswer(call -> base);
        bind(40L, "SKILL.md", "中文\n".getBytes(StandardCharsets.UTF_8));
        bind(41L, "old.txt", "旧文件\r\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void deletedAndAddedFilesReturnOnlyTheActuallyExistingSideAndPreserveLineEndings() {
        var changes = service.changes(1L, 20L, 72L, null, null);
        assertEquals("v1", changes.baseVersion());
        assertEquals(2, changes.items().size());
        var deleted = service.comparison(1L, 20L, 72L, null, null, "old.txt");
        assertEquals("DELETED", deleted.changeType());
        assertNull(deleted.newFile());
        assertEquals("旧文件\r\n", deleted.oldFile().content());
        var added = service.comparison(1L, 20L, 72L, null, null, "SKILL.md");
        assertNull(added.oldFile());
        assertEquals("中文\n", added.newFile().content());
    }

    @Test
    void blockedBaseDoesNotLeakPathsThroughSuccessorChangesButTargetFilesRemainReadable() {
        base.setStatus("BLOCKED");
        assertThrows(ClientException.class, () -> service.changes(1L, 20L, 72L, null, null));
        assertThrows(
                ClientException.class,
                () -> service.comparison(1L, 20L, 72L, null, null, "old.txt"));
        assertEquals(
                "SKILL.md",
                service.files(1L, 20L, 72L, null, null).items().getFirst().relativePath());
        verifyNoInteractions(store);
    }

    @Test
    void ownershipAndTargetStatusAreCheckedBeforeArtifactRead() {
        when(history.node(2L, 20L, 72L))
                .thenThrow(new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE));
        assertThrows(ClientException.class, () -> service.files(2L, 20L, 72L, null, null));
        target.setStatus("BLOCKED");
        assertThrows(ClientException.class, () -> service.files(1L, 20L, 72L, null, null));
        verifyNoInteractions(artifacts, store);
    }

    @Test
    void revokedBaseDuringArchiveReadIsRecheckedBeforeReturningBody() throws Exception {
        byte[] bytes = archive("old.txt", new byte[0]);
        bind(41L, "old.txt", new byte[0]);
        when(store.get(eq("artifact/41"), anyInt()))
                .thenAnswer(
                        call -> {
                            base.setStatus("BLOCKED");
                            return bytes;
                        });
        assertThrows(
                ClientException.class,
                () -> service.comparison(1L, 20L, 72L, null, null, "old.txt"));
    }

    @Test
    void storageFailuresNeverMasqueradeAsEmptyDiffAndUnsupportedSidesAreNotEmptyText()
            throws Exception {
        when(store.get(anyString(), anyInt()))
                .thenThrow(new ServerException(SkillErrorCodeEnum.UNAVAILABLE));
        assertThrows(
                ServerException.class,
                () -> service.comparison(1L, 20L, 72L, null, null, "SKILL.md"));
        bind(40L, "image.png", new byte[] {0, 1});
        var comparison = service.comparison(1L, 20L, 72L, null, null, "image.png");
        assertEquals("UNSUPPORTED", comparison.newFile().status());
        assertNull(comparison.newFile().content());
        assertEquals("ADDED", comparison.changeType());
    }

    @Test
    void initialEmptyBaseAndUnknownFileAreDistinctFromCorruptManifest() {
        target.setDiffBaseVersionId(null);
        assertNull(service.changes(1L, 20L, 72L, null, null).baseVersionId());
        assertThrows(
                ClientException.class,
                () -> service.content(1L, 20L, 72L, null, null, "missing.txt"));
        SkillArtifactDO corrupt = SkillTestFixture.artifact();
        corrupt.setManifestJson("{}");
        when(artifacts.selectById(40L)).thenReturn(corrupt);
        assertThrows(ServerException.class, () -> service.files(1L, 20L, 72L, null, null));
    }

    @Test
    void workspaceRequiresExactRevisionAndDoesNotAllowDraftOfDraft() {
        var owned =
                new SkillOwnedDraft(
                        SkillTestFixture.publisher(),
                        SkillTestFixture.skill(),
                        SkillTestFixture.draft());
        owned.draft().setArtifactId(40L);
        owned.draft().setStatus("READY");
        when(workspace.read(1L, 20L)).thenReturn(owned);
        assertEquals(1, service.files(1L, 20L, null, 2L, "RELEASE").items().size());
        assertThrows(ClientException.class, () -> service.files(1L, 20L, null, 1L, "RELEASE"));
        assertThrows(ClientException.class, () -> service.changes(1L, 20L, null, 2L, "DRAFT"));
        owned.skill().setStatus("ACTIVE");
        owned.skill().setCurrentVersionId(70L);
        owned.skill().setVersionSequence(2L);
        owned.draft().setSourceVersionId(72L);
        target.setVersionType("DRAFT");
        target.setSequenceNumber(2L);
        assertThrows(ClientException.class, () -> service.changes(1L, 20L, null, 2L, "DRAFT"));
    }

    private SkillVersionDO node(Long id, Long artifact) {
        SkillVersionDO value = new SkillVersionDO();
        value.setVersionId(id);
        value.setSkillId(20L);
        value.setVersionType("RELEASE");
        value.setReleaseNumber(1);
        value.setDraftNumber(0);
        value.setArtifactId(artifact);
        value.setStatus("AVAILABLE");
        return value;
    }

    private void bind(Long id, String path, byte[] content) throws Exception {
        byte[] bytes = archive(path, content);
        SkillArtifactDO artifact = SkillTestFixture.artifact();
        artifact.setArtifactId(id);
        artifact.setArchiveKey("artifact/" + id);
        artifact.setArchiveBytes((long) bytes.length);
        artifact.setArchiveSha256(DigestUtil.sha256Hex(bytes));
        artifact.setManifestJson(
                json.encode(
                        new SkillPackageManifest(
                                "root",
                                "x",
                                List.of(
                                        new SkillValidatedFile(
                                                path,
                                                DigestUtil.sha256Hex(content),
                                                content.length))),
                        8192));
        when(artifacts.selectById(id)).thenReturn(artifact);
        when(store.get(eq("artifact/" + id), anyInt())).thenReturn(bytes);
    }

    private byte[] archive(String path, byte[] content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry("root/" + path);
            entry.setTime(0L);
            zip.putNextEntry(entry);
            zip.write(content);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
