package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillArchivePreviewReaderTest {
    private final SkillArchivePreviewReader reader = new SkillArchivePreviewReader();

    @Test
    void exactEntryAndDigestAreRequiredIncludingForEmptyText() throws Exception {
        byte[] text = new byte[]{'a', '\n'};
        byte[] archive = SkillFileQueryServiceTest.archive("package/a.txt", text);
        SkillValidatedFile file = SkillFileQueryServiceTest.file("a.txt", text);
        assertArrayEquals(text, reader.read(archive, "package", file));
        assertThrows(ServerException.class, () -> reader.read(archive, "wrong-root", file));
        assertThrows(ServerException.class, () -> reader.read(archive, "package",
                new SkillValidatedFile("a.txt", file.sha256(), 3L)));
        assertThrows(ServerException.class, () -> reader.read(archive, "package",
                new SkillValidatedFile("a.txt", "0".repeat(64), text.length)));
        byte[] empty = SkillFileQueryServiceTest.archive("package/empty.txt", new byte[0]);
        assertArrayEquals(new byte[0], reader.read(empty, "package", SkillFileQueryServiceTest.file("empty.txt", new byte[0])));
    }

    @Test
    void corruptArchiveDirectoryAndOversizedActualEntryCannotProvidePreview() throws Exception {
        SkillValidatedFile file = SkillFileQueryServiceTest.file("a.txt", new byte[0]);
        assertThrows(ServerException.class, () -> reader.read(new byte[]{1, 2, 3}, "package", file));
        byte[] directory = SkillFileQueryServiceTest.archive("package/a.txt/", new byte[0]);
        assertThrows(ServerException.class, () -> reader.read(directory, "package",
                new SkillValidatedFile("a.txt/", file.sha256(), 0)));
        byte[] tooLarge = new byte[SkillFilePreviewPolicy.MAX_PREVIEW_BYTES + 1];
        byte[] archive = SkillFileQueryServiceTest.archive("package/a.txt", tooLarge);
        assertThrows(ServerException.class, () -> reader.read(archive, "package",
                SkillFileQueryServiceTest.file("a.txt", tooLarge)));
    }
}
