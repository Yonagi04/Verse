package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;
import java.io.IOException;
import java.io.InputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/** 只读取规范化 ZIP 的一个清单条目，不解压落盘或遍历解压其他文件。 */
final class SkillArchivePreviewReader {
    /** @return 已核对实际大小和摘要的文件字节；损坏快照按依赖不可用处理 */
    byte[] read(byte[] archive, String rootName, SkillValidatedFile file) {
        try (SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(archive);
             ZipFile zip = ZipFile.builder().setSeekableByteChannel(channel).get()) {
            ZipArchiveEntry entry = zip.getEntry(rootName + "/" + file.relativePath());
            if (entry == null || entry.isDirectory() || entry.isUnixSymlink()
                    || entry.getGeneralPurposeBit().usesEncryption() || !zip.canReadEntryData(entry)
                    || entry.getSize() != file.sizeBytes()) {
                throw unavailable();
            }
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(SkillFilePreviewPolicy.MAX_PREVIEW_BYTES + 1);
                if (bytes.length != file.sizeBytes() || bytes.length > SkillFilePreviewPolicy.MAX_PREVIEW_BYTES
                        || !DigestUtil.sha256Hex(bytes).equals(file.sha256())) {
                    throw unavailable();
                }
                return bytes;
            }
        } catch (IOException exception) {
            throw new ServerException("Skill文件快照不可读取", exception, SkillErrorCodeEnum.UNAVAILABLE);
        }
    }

    private ServerException unavailable() {
        return new ServerException("Skill文件快照校验失败", SkillErrorCodeEnum.UNAVAILABLE);
    }
}
