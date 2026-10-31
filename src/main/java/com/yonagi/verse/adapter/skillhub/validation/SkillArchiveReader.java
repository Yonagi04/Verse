package com.yonagi.verse.adapter.skillhub.validation;

import com.yonagi.verse.adapter.skillhub.validation.exception.SkillPackageValidationException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

/** 依据中央目录检查类型，依据实际读取字节而非声明大小控制解压资源。 */
final class SkillArchiveReader {
    private static final long MAX_UNPACKED_BYTES = SkillPackageLimitConstant.MAX_UNPACKED_BYTES;
    private static final int MAX_FILES = SkillPackageLimitConstant.MAX_FILE_COUNT;
    private static final int MAX_ENTRIES = 1000;
    private static final int BUFFER_BYTES = 8192;
    private static final int UNIX_TYPE_MASK = 0170000;
    private static final int UNIX_REGULAR = 0100000;
    private static final int UNIX_DIRECTORY = 0040000;
    private final SkillArchivePathPolicy pathPolicy = new SkillArchivePathPolicy();

    Map<String, byte[]> read(byte[] archive, SkillValidationControl control) throws IOException {
        new SkillArchiveStructurePolicy().check(archive, control);
        Map<String, byte[]> files = new TreeMap<>();
        Map<String, Boolean> paths = new HashMap<>();
        Map<String, String> spellings = new HashMap<>();
        long unpacked = 0L;
        int entries = 0;
        try (SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(archive);
             ZipFile zip = ZipFile.builder().setSeekableByteChannel(channel).get()) {
            Enumeration<ZipArchiveEntry> enumeration = zip.getEntries();
            while (enumeration.hasMoreElements()) {
                control.check();
                ZipArchiveEntry entry = enumeration.nextElement();
                if (++entries > MAX_ENTRIES) {
                    throw limit();
                }
                checkType(zip, entry);
                String rawName = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(entry.getRawName())).toString();
                String path = pathPolicy.validate(rawName, entry.isDirectory());
                if (!rawName.equals(entry.getName())) {
                    throw collision();
                }
                registerPath(paths, spellings, path, entry.isDirectory());
                if (entry.isDirectory()) {
                    continue;
                }
                if (files.size() >= MAX_FILES || entry.getSize() > MAX_UNPACKED_BYTES - unpacked) {
                    throw limit();
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) Math.min(entry.getSize(), BUFFER_BYTES));
                CRC32 crc = new CRC32();
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[BUFFER_BYTES];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        control.check();
                        unpacked += count;
                        if (unpacked > MAX_UNPACKED_BYTES) {
                            throw limit();
                        }
                        bytes.write(buffer, 0, count);
                        crc.update(buffer, 0, count);
                    }
                }
                if (crc.getValue() != entry.getCrc() || bytes.size() != entry.getSize()) {
                    throw new SkillPackageValidationException("INVALID_ARCHIVE", "ZIP条目大小或校验和不匹配");
                }
                files.put(path, bytes.toByteArray());
            }
        }
        if (files.isEmpty()) {
            throw new SkillPackageValidationException("EMPTY_ARCHIVE", "ZIP没有可发布文件");
        }
        control.check();
        return files;
    }

    private void checkType(ZipFile zip, ZipArchiveEntry entry) {
        int type = entry.getUnixMode() & UNIX_TYPE_MASK;
        if (entry.isUnixSymlink() || entry.getGeneralPurposeBit().usesEncryption() || !zip.canReadEntryData(entry)
                || (type != 0 && type != (entry.isDirectory() ? UNIX_DIRECTORY : UNIX_REGULAR))
                || entry.getSize() < 0 || entry.getCrc() < 0) {
            throw new SkillPackageValidationException("UNSUPPORTED_ENTRY", "ZIP包含链接、加密或不支持的文件类型");
        }
        if (entry.isDirectory() && entry.getSize() != 0) {
            throw new SkillPackageValidationException("UNSUPPORTED_ENTRY", "目录条目不能携带文件数据");
        }
    }

    private void registerPath(Map<String, Boolean> paths, Map<String, String> spellings,
                              String path, boolean directory) {
        String key = pathPolicy.collisionKey(path);
        if (paths.putIfAbsent(key, directory) != null) {
            throw collision();
        }
        String[] segments = path.split("/");
        StringBuilder spelling = new StringBuilder();
        for (String segment : segments) {
            if (!spelling.isEmpty()) {
                spelling.append('/');
            }
            spelling.append(segment);
            String raw = spelling.toString();
            String previous = spellings.putIfAbsent(pathPolicy.collisionKey(raw), raw);
            if (previous != null && !previous.equals(raw)) {
                throw collision();
            }
        }
        int slash = key.indexOf('/');
        while (slash >= 0) {
            if (Boolean.FALSE.equals(paths.get(key.substring(0, slash)))) {
                throw collision();
            }
            slash = key.indexOf('/', slash + 1);
        }
        if (!directory && paths.keySet().stream().anyMatch(other -> other.startsWith(key + "/"))) {
            throw collision();
        }
    }

    private SkillPackageValidationException collision() {
        return new SkillPackageValidationException("PATH_COLLISION", "ZIP包含重复、别名或文件目录冲突");
    }

    private SkillPackageValidationException limit() {
        return new SkillPackageValidationException("LIMIT_EXCEEDED", "ZIP超过解压大小、文件数或目录数限制");
    }
}
