package com.yonagi.verse.adapter.skillhub.validation;

import com.yonagi.verse.adapter.skillhub.validation.exception.SkillPackageValidationException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** 路径不落盘；提前拒绝跨平台别名、控制字符、设备名与目录越界。 */
final class SkillArchivePathPolicy {
    private static final int MAX_PATH_LENGTH = 577;
    private static final int MAX_SEGMENT_LENGTH = 128;
    private static final int MAX_DEPTH = 11;
    private static final Pattern DEVICE = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\..*)?");

    String validate(String raw, boolean directory) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_PATH_LENGTH || raw.startsWith("/")
                || raw.indexOf('\\') >= 0 || raw.indexOf(':') >= 0
                || !Normalizer.isNormalized(raw, Normalizer.Form.NFC)) {
            throw invalid();
        }
        String path = directory && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        String[] segments = path.split("/", -1);
        if (segments.length > MAX_DEPTH) {
            throw invalid();
        }
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || segment.length() > MAX_SEGMENT_LENGTH || segment.endsWith(".") || segment.endsWith(" ")
                    || DEVICE.matcher(segment).matches()) {
                throw invalid();
            }
            for (int index = 0; index < segment.length(); index++) {
                char character = segment.charAt(index);
                if (Character.isISOControl(character) || Character.getType(character) == Character.FORMAT
                        || "<>\"|?*".indexOf(character) >= 0) {
                    throw invalid();
                }
            }
        }
        return path;
    }

    String collisionKey(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    private SkillPackageValidationException invalid() {
        return new SkillPackageValidationException("UNSAFE_PATH", "ZIP包含越界、冲突或不兼容的路径");
    }
}
