package com.yonagi.verse.adapter.skillhub.validation;

import com.yonagi.verse.adapter.skillhub.validation.exception.SkillPackageValidationException;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第三方Entry分配前的真实目录预算、偏移及ZIP64兼容。 */
class SkillArchiveStructurePolicyTest {
    private final SkillArchiveStructurePolicy policy = new SkillArchiveStructurePolicy();
    private final SkillValidationControl control = new SkillValidationControl(() -> true);

    @Test
    void countsActualDirectoryRecordsEvenWhenDeclaredCountLies() throws Exception {
        byte[] valid = archive(1000);
        policy.check(valid, control);
        byte[] excessive = archive(1001);
        rejects("LIMIT_EXCEEDED", excessive);
        int end = excessive.length - 22;
        view(excessive).putShort(end + 8, (short) 1).putShort(end + 10, (short) 1);
        rejects("LIMIT_EXCEEDED", excessive);
        byte[] mismatch = archive(2);
        end = mismatch.length - 22;
        view(mismatch).putShort(end + 8, (short) 1).putShort(end + 10, (short) 1);
        rejects("INVALID_ARCHIVE", mismatch);
    }

    @Test
    void rejectsMalformedOffsetsSignaturesAndTruncatedRecordsBeforeLibraryAllocation() throws Exception {
        byte[] original = archive(1);
        int end = original.length - 22;
        int central = view(original).getInt(end + 16);
        for (int[] mutation : new int[][]{{end + 16, -1}, {end + 12, -1}, {end + 12, 1},
                {end, 0}, {central, 0}, {end + 16, end}, {central + 30, -1}}) {
            byte[] changed = original.clone();
            view(changed).putInt(mutation[0], mutation[1]);
            rejects("INVALID_ARCHIVE", changed);
        }
        for (int offset : new int[]{4, 6, 8, 20}) {
            byte[] changed = original.clone();
            view(changed).putShort(end + offset, (short) 2);
            rejects("INVALID_ARCHIVE", changed);
        }
        rejects("INVALID_ARCHIVE", Arrays.copyOf(original, original.length - 1));
        rejects("INVALID_ARCHIVE", new byte[0]);
        byte[] name = original.clone();
        view(name).putShort(central + 28, (short) 2309);
        rejects("UNSAFE_PATH", name);
    }

    @Test
    void acceptsZip64AndRejectsInvalidExtendedDirectoryBoundsAndCounts() throws Exception {
        byte[] original = zip64();
        policy.check(original, control);
        assertTrue(new SkillPackageValidator().validate(original).success());
        int end = original.length - 22;
        int locator = end - 20;
        int header = (int) view(original).getLong(locator + 8);
        byte[] sentinel = original.clone();
        view(sentinel).putShort(end + 8, (short) 0xffff).putShort(end + 10, (short) 0xffff)
                .putInt(end + 12, -1).putInt(end + 16, -1);
        assertTrue(new SkillPackageValidator().validate(sentinel).success());
        for (int[] mutation : new int[][]{{locator + 4, 1}, {locator + 16, 2}, {header, 0},
                {header + 16, 1}, {header + 20, 1}, {header + 24, 2}, {header + 32, 2},
                {header + 40, 1}, {header + 48, 1}}) {
            byte[] changed = original.clone();
            view(changed).putInt(mutation[0], mutation[1]);
            rejects("INVALID_ARCHIVE", changed);
        }
        for (int offset : new int[]{locator + 8, header + 4, header + 32, header + 40, header + 48}) {
            byte[] changed = original.clone();
            view(changed).putLong(offset, Long.MAX_VALUE);
            rejects("INVALID_ARCHIVE", changed);
            view(changed).putLong(offset, -1L);
            rejects("INVALID_ARCHIVE", changed);
        }
        byte[] small = original.clone();
        view(small).putLong(header + 4, 1L);
        rejects("INVALID_ARCHIVE", small);
    }

    @Test
    void acceptsCommentAndChinesePathWithinOriginalCharacterLimits() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String path = "中".repeat(120) + "/" + "文".repeat(120) + "/file.bin";
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setComment("comment PK is ordinary text");
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write(markdown());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(path));
            zip.write(1);
            zip.closeEntry();
        }
        assertEquals(2, new SkillPackageValidator().validate(bytes.toByteArray()).files().size());
    }

    @Test
    void rejectsNestedEndSignatureInsteadOfValidatingADifferentDirectoryFromCompress() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setComment("x".repeat(64));
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write(markdown());
            zip.closeEntry();
        }
        byte[] archive = bytes.toByteArray();
        int nestedEnd = archive.length - 30;
        view(archive).putInt(nestedEnd, 0x06054b50).putShort(nestedEnd + 20, (short) 0);
        rejects("INVALID_ARCHIVE", archive);
    }

    @Test
    void directoryTraversalChecksCancellationBeforeConstructingLibraryEntries() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        SkillValidationControl cancelled = new SkillValidationControl(() -> checks.incrementAndGet() < 3);
        assertThrows(SkillValidationCancelledException.class, () -> policy.check(archive(1000), cancelled));
    }

    private byte[] archive(int count) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int index = 0; index < count; index++) {
                zip.putNextEntry(new ZipEntry("d" + index + "/"));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private byte[] zip64() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes)) {
            zip.setUseZip64(Zip64Mode.Always);
            zip.putArchiveEntry(new ZipArchiveEntry("SKILL.md"));
            zip.write(markdown());
            zip.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    private byte[] markdown() {
        return "---\nname: test-skill\ndescription: test\n---\n".getBytes(StandardCharsets.UTF_8);
    }

    private ByteBuffer view(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private void rejects(String code, byte[] bytes) {
        assertEquals(code, assertThrows(SkillPackageValidationException.class,
                () -> policy.check(bytes, control)).getCode());
    }
}
