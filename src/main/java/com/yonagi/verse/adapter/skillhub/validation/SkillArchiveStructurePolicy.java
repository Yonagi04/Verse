package com.yonagi.verse.adapter.skillhub.validation;

import com.yonagi.verse.adapter.skillhub.validation.exception.SkillPackageValidationException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 在第三方库分配Entry集合前限制真实中央目录，所有偏移先在有界输入内核验。 */
final class SkillArchiveStructurePolicy {
    private static final int END_SIGNATURE = 0x06054b50;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
    private static final int ZIP64_END_SIGNATURE = 0x06064b50;
    private static final int CENTRAL_SIGNATURE = 0x02014b50;
    private static final int END_BYTES = 22;
    private static final int MAX_COMMENT_BYTES = 65535;
    private static final int MAX_ENTRIES = 1000;
    private static final int CENTRAL_HEADER_BYTES = 46;
    // 原路径最多577个Java字符；UTF-8字节预算不能误伤中文或补充平面字符。
    private static final int MAX_NAME_BYTES = 577 * 4;
    private static final long UINT_SENTINEL = 0xffffffffL;
    private static final int USHORT_SENTINEL = 0xffff;

    void check(byte[] archive, SkillValidationControl control) {
        ByteBuffer data = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int end = findEnd(data, control);
        if (unsignedShort(data, end + 4) != 0 || unsignedShort(data, end + 6) != 0) {
            throw invalid();
        }
        long count = unsignedShort(data, end + 10);
        long size = unsignedInt(data, end + 12);
        long offset = unsignedInt(data, end + 16);
        int directoryEnd = end;
        boolean zip64 = end >= 20 && data.getInt(end - 20) == ZIP64_LOCATOR_SIGNATURE;
        if (zip64) {
            int locator = end - 20;
            if (unsignedInt(data, locator + 4) != 0 || unsignedInt(data, locator + 16) != 1) {
                throw invalid();
            }
            long zip64Offset = positiveLong(data, locator + 8);
            range(zip64Offset, 56, locator);
            int header = (int) zip64Offset;
            long recordSize = positiveLong(data, header + 4);
            if (data.getInt(header) != ZIP64_END_SIGNATURE || recordSize < 44
                    || recordSize != locator - zip64Offset - 12
                    || unsignedInt(data, header + 16) != 0 || unsignedInt(data, header + 20) != 0) {
                throw invalid();
            }
            long zip64Count = positiveLong(data, header + 32);
            long zip64Size = positiveLong(data, header + 40);
            long zip64Directory = positiveLong(data, header + 48);
            if (positiveLong(data, header + 24) != zip64Count
                    || !matches(count, zip64Count, USHORT_SENTINEL)
                    || !matches(unsignedShort(data, end + 8), zip64Count, USHORT_SENTINEL)
                    || !matches(size, zip64Size, UINT_SENTINEL)
                    || !matches(offset, zip64Directory, UINT_SENTINEL)) {
                throw invalid();
            }
            count = zip64Count;
            size = zip64Size;
            offset = zip64Directory;
            directoryEnd = header;
        } else if (unsignedShort(data, end + 8) != count || count == USHORT_SENTINEL
                || size == UINT_SENTINEL || offset == UINT_SENTINEL) {
            throw invalid();
        }
        range(offset, size, directoryEnd);
        if (offset + size != directoryEnd) {
            throw invalid();
        }
        // 声明过大直接拒绝；声明偏小仍须逐条扫描，不能让伪造计数绕过实际限额。
        if (count > MAX_ENTRIES) {
            throw limit();
        }
        int actual = 0;
        long position = offset;
        while (position < directoryEnd) {
            control.check();
            if (++actual > MAX_ENTRIES) {
                throw limit();
            }
            range(position, CENTRAL_HEADER_BYTES, directoryEnd);
            int entry = (int) position;
            if (data.getInt(entry) != CENTRAL_SIGNATURE) {
                throw invalid();
            }
            int nameBytes = unsignedShort(data, entry + 28);
            int extraBytes = unsignedShort(data, entry + 30);
            int commentBytes = unsignedShort(data, entry + 32);
            if (nameBytes > MAX_NAME_BYTES) {
                throw new SkillPackageValidationException("UNSAFE_PATH", "ZIP路径超过长度限制");
            }
            long length = (long) CENTRAL_HEADER_BYTES + nameBytes + extraBytes + commentBytes;
            range(position, length, directoryEnd);
            position += length;
        }
        if (actual != count) {
            throw invalid();
        }
        control.check();
    }

    private int findEnd(ByteBuffer data, SkillValidationControl control) {
        int minimum = Math.max(0, data.limit() - END_BYTES - MAX_COMMENT_BYTES);
        for (int position = data.limit() - END_BYTES; position >= minimum; position--) {
            control.accessed();
            if (data.getInt(position) == END_SIGNATURE) {
                // 与Compress从尾部遇到的首个签名保持同一视图；不能跳过伪记录再回退。
                if (position + END_BYTES + unsignedShort(data, position + 20) != data.limit()) {
                    throw invalid();
                }
                return position;
            }
        }
        throw invalid();
    }

    private boolean matches(long ordinary, long extended, long sentinel) {
        return ordinary == sentinel || ordinary == extended;
    }

    private int unsignedShort(ByteBuffer data, int offset) {
        return Short.toUnsignedInt(data.getShort(offset));
    }

    private long unsignedInt(ByteBuffer data, int offset) {
        return Integer.toUnsignedLong(data.getInt(offset));
    }

    private long positiveLong(ByteBuffer data, int offset) {
        long value = data.getLong(offset);
        if (value < 0) {
            throw invalid();
        }
        return value;
    }

    private void range(long offset, long length, long end) {
        if (offset < 0 || length < 0 || offset > end || length > end - offset) {
            throw invalid();
        }
    }

    private SkillPackageValidationException invalid() {
        return new SkillPackageValidationException("INVALID_ARCHIVE", "ZIP中央目录结构不合法");
    }

    private SkillPackageValidationException limit() {
        return new SkillPackageValidationException("LIMIT_EXCEEDED", "ZIP中央目录条目超过限制");
    }
}
