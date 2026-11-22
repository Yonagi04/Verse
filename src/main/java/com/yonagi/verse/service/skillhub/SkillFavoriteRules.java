package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.query.SkillFavoriteCondition;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 收藏命名、容量及有界查询规则；不依赖持久化。 */
public final class SkillFavoriteRules {
    public static final long DEFAULT_FOLDER = 0L;
    public static final String DEFAULT_NAME = "默认收藏夹";
    public static final int FOLDER_LIMIT = 20;
    public static final int FAVORITE_LIMIT = 1000;
    public static final int STATUS_LIMIT = 24;
    private static final int NAME_LIMIT = 20;
    private static final int KEYWORD_LIMIT = 80;
    private static final int MAX_PAGE = 10000;
    private static final Set<Integer> PAGE_SIZES = Set.of(12, 24);

    private SkillFavoriteRules() { }

    /** @return 去除 ECMAScript trim 空白并 NFC 规范化的合法夹名。 */
    public static String name(String value) {
        String normalized = clean(value);
        if (normalized == null || normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length()) > NAME_LIMIT
                || normalized.codePoints().anyMatch(Character::isISOControl)
                || Set.of(DEFAULT_NAME, "全部收藏").contains(normalized)) {
            throw new ClientException("收藏夹名称须为 1–20 个字符，且不能使用系统保留名称", SkillErrorCodeEnum.INVALID_INPUT);
        }
        return normalized;
    }

    /** @return 与二进制唯一索引一致的名称键。 */
    public static String nameKey(String normalized) {
        return Normalizer.normalize(normalized.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }

    /** @return 有界、完成 LIKE 字面量转义的稳定查询参数。 */
    public static SkillFavoriteCondition condition(Long folderId, String keyword, String sort, int page, int size) {
        folderId(folderId, true);
        String normalized = clean(keyword);
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        if (page < 1 || page > MAX_PAGE || !PAGE_SIZES.contains(size)
                || !Set.of("saved", "updated").contains(sort == null ? "" : sort)
                || (normalized != null && normalized.codePointCount(0, normalized.length()) > KEYWORD_LIMIT)) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        String contains = normalized == null ? null : "%" + normalized.replace("!", "!!")
                .replace("%", "!%").replace("_", "!_") + "%";
        return new SkillFavoriteCondition(folderId, normalized, contains, sort, page, size, (page - 1) * size);
    }

    /** @return 原始数量校验后排序去重的状态查询键。 */
    public static List<Long> statusIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > STATUS_LIMIT
                || ids.stream().anyMatch(id -> id == null || id < 1)) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        return ids.stream().distinct().sorted().toList();
    }

    /** 验证可写目标；null 仅可用于全部收藏查询。 */
    public static void folderId(Long id, boolean allowAll) {
        if ((id == null && !allowAll) || (id != null && id < DEFAULT_FOLDER)) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
    }

    /** 验证真实关系或资源标识。 */
    public static void positiveId(Long id) {
        if (id == null || id < 1) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && trimSpace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && trimSpace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return Normalizer.normalize(value.substring(start, end), Normalizer.Form.NFC);
    }

    private static boolean trimSpace(int point) {
        return point == 0xFEFF || point == 0x0009 || point == 0x000A || point == 0x000B
                || point == 0x000C || point == 0x000D || point == 0x2028 || point == 0x2029
                || Character.getType(point) == Character.SPACE_SEPARATOR;
    }
}
