package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.entity.skillhub.SkillArtifactDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillArtifactMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillDownloadQueryMapper;
import com.yonagi.verse.dao.projection.SkillDownloadProjection;
import com.yonagi.verse.dto.resp.SkillFileContentRespDTO;
import com.yonagi.verse.dto.resp.SkillFilesRespDTO;
import com.yonagi.verse.service.skillhub.port.SkillArtifactStore;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 隔离 Mapper/S3/原授权，只验证新增用例决策，不声称证明基础设施一致性。 */
class SkillFileQueryServiceTest {
    private static final String ROOT = "package-name-different-from-slug";
    private final SkillDownloadQueryMapper query = mock(SkillDownloadQueryMapper.class);
    private final SkillDownloadAuthorizationService authorization = mock(SkillDownloadAuthorizationService.class);
    private final SkillArtifactMapper artifacts = mock(SkillArtifactMapper.class);
    private final SkillArtifactStore store = mock(SkillArtifactStore.class);
    private final SkillGithubSnapshotCodec codec = new SkillGithubSnapshotCodec(new ObjectMapper());
    private final SkillFileQueryService service = new SkillFileQueryService(query, authorization, artifacts, codec, store);
    private SkillArtifactDO artifact;
    private SkillDownloadResource resource;

    @BeforeEach
    void prepare() throws Exception {
        artifact = new SkillArtifactDO();
        artifact.setArtifactId(40L);
        artifact.setStatus("READY");
        artifact.setArchiveKey("skillhub/v1/artifact/40/test.zip");
        SkillDownloadProjection projection = new SkillDownloadProjection();
        projection.setArtifactId(40L);
        when(query.selectAvailable(20L, 70L)).thenReturn(projection);
        when(artifacts.selectById(40L)).thenReturn(artifact);
        bind("src/main.py", "print('中文')\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void listsStablePathsAndPackageRootFromTheExactPublishedSnapshot() {
        List<SkillValidatedFile> files = List.of(file("z.txt", new byte[0]), file("SKILL.md", new byte[0]));
        manifest(new SkillPackageManifest(ROOT, "说明", files));
        SkillFilesRespDTO result = service.files(20L, 70L);
        assertEquals("70", result.versionId());
        assertEquals(ROOT, result.rootName());
        assertEquals(1024 * 1024, result.maxPreviewBytes());
        assertEquals(List.of("SKILL.md", "z.txt"), result.items().stream().map(value -> value.relativePath()).toList());
        assertEquals(DigestUtil.sha256Hex(new byte[0]), result.items().getFirst().sha256());
        verify(store, never()).get(anyString(), anyInt());
    }

    @Test
    void previewsThePackageRootRatherThanSlugAndKeepsOriginalTextAndVersion() {
        SkillFileContentRespDTO result = service.content(20L, 70L, "src/main.py");
        assertEquals("70", result.versionId());
        assertEquals("src/main.py", result.relativePath());
        assertEquals("READY", result.status());
        assertEquals("CODE", result.kind());
        assertEquals("python", result.language());
        assertEquals("print('中文')\n", result.content());
        assertEquals(result.content().getBytes(StandardCharsets.UTF_8).length, result.sizeBytes());
    }

    @Test
    void supportsEmptyAndExactlyOneMiBButDoesNotReadLargerOrUnsupportedFiles() throws Exception {
        bind("empty.txt", new byte[0]);
        assertEquals("", service.content(20L, 70L, "empty.txt").content());
        byte[] maximum = new byte[SkillFilePreviewPolicy.MAX_PREVIEW_BYTES];
        Arrays.fill(maximum, (byte) 'a');
        bind("max.txt", maximum);
        assertEquals(maximum.length, service.content(20L, 70L, "max.txt").content().length());
        manifest(new SkillPackageManifest(ROOT, "说明", List.of(
                new SkillValidatedFile("large.txt", "a".repeat(64), maximum.length + 1L),
                new SkillValidatedFile("office.docx", "b".repeat(64), 10L))));
        when(store.get(anyString(), anyInt())).thenThrow(new AssertionError("metadata-only result must not read S3"));
        assertEquals("TOO_LARGE", service.content(20L, 70L, "large.txt").status());
        SkillFileContentRespDTO unsupported = service.content(20L, 70L, "office.docx");
        assertEquals("UNSUPPORTED", unsupported.status());
        assertEquals("UNSUPPORTED", unsupported.kind());
        assertNull(unsupported.content());
    }

    @Test
    void rejectsBinaryDisguisedAsTextWithoutReturningItsContent() throws Exception {
        bind("pretend.txt", new byte[]{'a', 0, 'b'});
        SkillFileContentRespDTO result = service.content(20L, 70L, "pretend.txt");
        assertEquals("UNSUPPORTED", result.status());
        assertNull(result.content());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "/etc/passwd", "../SKILL.md", "a/../x.txt", "a/./x.txt", "a//x.txt",
            "a/", "C:/x.txt", "a\\x.txt", "a\u0000.txt", "https://example.com/a.txt"})
    void rejectsUnsafePathsBeforeAnyResourceLookup(String path) {
        ClientException failure = assertThrows(ClientException.class, () -> service.content(20L, 70L, path));
        assertEquals(SkillErrorCodeEnum.INVALID_INPUT.code(), failure.getErrorCode());
        verify(query, never()).selectAvailable(20L, 70L);
    }

    @Test
    void longPathAndMissingFileAreDifferentInputAndVisibilityFailures() {
        assertEquals(SkillErrorCodeEnum.INVALID_INPUT.code(), assertThrows(ClientException.class,
                () -> service.content(20L, 70L, "x".repeat(513))).getErrorCode());
        assertEquals(SkillErrorCodeEnum.NOT_ACCESSIBLE.code(), assertThrows(ClientException.class,
                () -> service.content(20L, 70L, "missing.txt")).getErrorCode());
        verify(store, never()).get(anyString(), anyInt());
    }

    @Test
    void wrongSkillUnavailableVersionAndUnpublishDuringReadNeverExposeContent() {
        assertDenied(() -> service.files(21L, 70L));
        when(authorization.authorize(70L)).thenThrow(new ClientException(SkillErrorCodeEnum.VERSION_UNAVAILABLE));
        assertDenied(() -> service.files(20L, 70L));
        doReturn(resource).doThrow(new ClientException(SkillErrorCodeEnum.VERSION_UNAVAILABLE))
                .when(authorization).authorize(70L);
        assertDenied(() -> service.content(20L, 70L, "src/main.py"));
    }

    @Test
    void finalSnapshotChangeAndOtherAuthorizationFailuresRemainFailures() {
        SkillDownloadResource changed = new SkillDownloadResource(70L, "changed.zip", "other", "other", 1L);
        when(authorization.authorize(70L)).thenReturn(resource).thenReturn(changed);
        assertDenied(() -> service.files(20L, 70L));
        when(authorization.authorize(70L)).thenThrow(new ClientException(SkillErrorCodeEnum.RATE_LIMITED));
        assertEquals(SkillErrorCodeEnum.RATE_LIMITED.code(), assertThrows(ClientException.class,
                () -> service.files(20L, 70L)).getErrorCode());
    }

    @Test
    void storageFailureOrCorruptArchiveFailsAndReleasesPreviewBudget() {
        when(store.get(anyString(), anyInt())).thenThrow(new ServerException(SkillErrorCodeEnum.UNAVAILABLE));
        for (int index = 0; index < 3; index++) {
            assertThrows(ServerException.class, () -> service.content(20L, 70L, "src/main.py"));
        }
        doReturn(new byte[]{1}).when(store).get(anyString(), anyInt());
        assertThrows(ServerException.class, () -> service.content(20L, 70L, "src/main.py"));
        byte[] sameLength = new byte[(int) resource.bytes()];
        when(store.get(anyString(), anyInt())).thenReturn(sameLength);
        assertThrows(ServerException.class, () -> service.content(20L, 70L, "src/main.py"));
    }

    @Test
    void missingOrMismatchedArtifactCannotProvideAFileList() {
        when(artifacts.selectById(40L)).thenReturn(null);
        assertThrows(ServerException.class, () -> service.files(20L, 70L));
        when(artifacts.selectById(40L)).thenReturn(artifact);
        artifact.setStatus("DELETED");
        assertThrows(ServerException.class, () -> service.files(20L, 70L));
        artifact.setStatus("READY"); artifact.setArchiveKey("wrong");
        assertThrows(ServerException.class, () -> service.files(20L, 70L));
        artifact.setArchiveKey(resource.archiveKey()); artifact.setArchiveSha256("wrong");
        assertThrows(ServerException.class, () -> service.files(20L, 70L));
        artifact.setArchiveSha256(resource.sha256()); artifact.setArchiveBytes(0L);
        assertThrows(ServerException.class, () -> service.files(20L, 70L));
    }

    @Test
    void malformedOrUnboundedManifestIsADependencyFailure() {
        artifact.setManifestJson(null);
        assertThrows(ServerException.class, () -> service.files(20L, 70L));
        List<SkillPackageManifest> malformed = new ArrayList<>();
        malformed.add(new SkillPackageManifest(null, "", List.of()));
        malformed.add(new SkillPackageManifest(" ", "", List.of()));
        malformed.add(new SkillPackageManifest(ROOT, "", null));
        malformed.add(new SkillPackageManifest(ROOT, "", java.util.Collections.nCopies(501, file("a.txt", new byte[0]))));
        malformed.add(new SkillPackageManifest(ROOT, "", Arrays.asList((SkillValidatedFile) null)));
        malformed.add(new SkillPackageManifest(ROOT, "", List.of(new SkillValidatedFile(null, "hash", 1L))));
        malformed.add(new SkillPackageManifest(ROOT, "", List.of(new SkillValidatedFile("a.txt", null, 1L))));
        malformed.add(new SkillPackageManifest(ROOT, "", List.of(new SkillValidatedFile("a.txt", "hash", -1L))));
        for (SkillPackageManifest manifest : malformed) {
            manifest(manifest);
            assertThrows(ServerException.class, () -> service.files(20L, 70L));
        }
        artifact.setManifestJson("{broken");
        assertThrows(ServerException.class, () -> service.files(20L, 70L));
    }

    @Test
    void twoInFlightReadsRejectThirdAndBudgetRecoversAfterTheyFinish() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(store.get(anyString(), anyInt())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        });
        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(this::readFailsSafely);
        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(this::readFailsSafely);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(first.isDone());
            assertEquals(SkillErrorCodeEnum.RATE_LIMITED.code(), assertThrows(ClientException.class,
                    () -> service.content(20L, 70L, "src/main.py")).getErrorCode());
        } finally { release.countDown(); }
        assertTrue(first.get(5, TimeUnit.SECONDS));
        assertTrue(second.get(5, TimeUnit.SECONDS));
        assertThrows(ServerException.class, () -> service.content(20L, 70L, "src/main.py"));
    }

    private boolean readFailsSafely() {
        assertThrows(ServerException.class, () -> service.content(20L, 70L, "src/main.py"));
        return true;
    }

    private void bind(String path, byte[] bytes) throws Exception {
        byte[] archive = archive(ROOT + "/" + path, bytes);
        resource = new SkillDownloadResource(70L, "unrelated-slug.zip", artifact.getArchiveKey(),
                DigestUtil.sha256Hex(archive), archive.length);
        artifact.setArchiveSha256(resource.sha256());
        artifact.setArchiveBytes(resource.bytes());
        manifest(new SkillPackageManifest(ROOT, "说明", List.of(file(path, bytes))));
        when(authorization.authorize(70L)).thenReturn(resource);
        when(store.get(anyString(), anyInt())).thenReturn(archive);
    }

    private void manifest(SkillPackageManifest manifest) {
        artifact.setManifestJson(codec.encode(manifest, 256 * 1024));
    }

    static byte[] archive(String path, byte[] bytes) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(path)); zip.write(bytes); zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    static SkillValidatedFile file(String path, byte[] bytes) {
        return new SkillValidatedFile(path, DigestUtil.sha256Hex(bytes), bytes.length);
    }

    private void assertDenied(org.junit.jupiter.api.function.Executable operation) {
        assertEquals(SkillErrorCodeEnum.NOT_ACCESSIBLE.code(),
                assertThrows(ClientException.class, operation).getErrorCode());
    }
}
