package com.yonagi.verse.service.skillhub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.mapper.skillhub.SkillPublicQueryMapper;
import com.yonagi.verse.dao.projection.SkillPublicProjection;
import com.yonagi.verse.dao.projection.SkillRevisionTagProjection;
import com.yonagi.verse.dao.query.SkillSearchCondition;
import com.yonagi.verse.dto.req.SkillSearchReqDTO;
import com.yonagi.verse.dto.resp.SkillCardRespDTO;
import com.yonagi.verse.dto.resp.SkillDetailRespDTO;
import com.yonagi.verse.dto.resp.SkillOptionRespDTO;
import com.yonagi.verse.dto.resp.SkillOptionsRespDTO;
import com.yonagi.verse.dto.resp.SkillPageRespDTO;
import com.yonagi.verse.dto.resp.SkillPublisherRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 公开查询用例。不缓存可用状态，所有SQL先过滤后分页，不依赖租户上下文。 */
@Service
@RequiredArgsConstructor
public class SkillQueryService {
    private static final int MAX_HANDLE_LENGTH = 32;
    private static final int MAX_SLUG_LENGTH = 64;
    private static final int MAX_FILTER_LENGTH = 32;
    private static final int MAX_KEYWORD_LENGTH = 80;
    private static final int MAX_PAGE = 10000;
    private static final int MAX_TAGS = 5;
    private static final int DEFAULT_PAGE_SIZE = 12;
    private static final String ZIP_SOURCE = "ZIP";
    private static final Set<Integer> PAGE_SIZES = Set.of(12, 24);
    private static final Set<String> SORTS = Set.of("updated", "relevance");
    private final SkillPublicQueryMapper publicQueryMapper;
    private final ObjectMapper objectMapper;

    /**
     * @param input 公开筛选条件，默认页大小12
     * @return 只包含当前可用内容的稳定分页
     */
    public SkillPageRespDTO<SkillCardRespDTO> search(SkillSearchReqDTO input) {
        SkillSearchCondition condition = normalize(input);
        long total = publicQueryMapper.countAvailable(condition);
        if (total == 0) {
            return new SkillPageRespDTO<>(condition.page(), condition.size(), 0L, List.of());
        }
        List<SkillPublicProjection> rows = publicQueryMapper.selectAvailablePage(condition);
        Map<Long, List<String>> tags = readTags(rows);
        List<SkillCardRespDTO> items = rows.stream().map(row -> card(row, tags)).toList();
        return new SkillPageRespDTO<>(condition.page(), condition.size(), total, items);
    }

    /**
     * @param skillId 稳定公开Skill标识
     * @return 当前版本详情或已下架的有限占位
     */
    public SkillDetailRespDTO detail(Long skillId) {
        SkillPublicProjection row = publicQueryMapper.selectAvailableDetail(skillId);
        if (row == null) {
            row = publicQueryMapper.selectPublishedPlaceholder(skillId);
            if (row == null) {
                throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
            }
            return new SkillDetailRespDTO(card(row, readTags(List.of(row))), "UNAVAILABLE",
                    null, null, null, null, null, null, null, null);
        }
        JsonNode declarations;
        try {
            declarations = objectMapper.readTree(row.getDeclarationsJson());
        } catch (JsonProcessingException exception) {
            throw new ServerException("Skill发布快照不可读取", exception, SkillErrorCodeEnum.UNAVAILABLE);
        }
        if (!declarations.isObject() || !declarations.path("license").isTextual()) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        return new SkillDetailRespDTO(card(row, readTags(List.of(row))), "AVAILABLE", row.getSkillMarkdown(),
                declarations.path("license").textValue(), declarations.path("originalAuthor").textValue(),
                declarations.path("sourceUrl").textValue(), declarations.path("compatibility").textValue(),
                declarations.path("dependencies").textValue(), row.getArchiveBytes(), row.getArchiveSha256(),
                new SkillGithubSnapshotCodec(objectMapper).source(row.getSourceType(), row.getSourceJson()));
    }

    /**
     * @param handle 用户已公开的作者标识
     * @param slug 作者下的固定Skill标识
     * @return 曾公开资源的稳定标识；私有草稿不暴露
     */
    public String lookup(String handle, String slug) {
        if (handle == null || handle.length() > MAX_HANDLE_LENGTH || slug == null || slug.length() > MAX_SLUG_LENGTH) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        Long skillId = publicQueryMapper.selectPublishedId(handle, slug);
        if (skillId == null) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        return skillId.toString();
    }

    /**
     * @return 低成本实时词表及唯一一套资源上限
     */
    public SkillOptionsRespDTO options() {
        List<SkillOptionRespDTO> categories = publicQueryMapper.selectCategories().stream()
                .map(item -> new SkillOptionRespDTO(item.getId(), item.getName())).toList();
        List<SkillOptionRespDTO> tags = publicQueryMapper.selectTags().stream()
                .map(item -> new SkillOptionRespDTO(item.getId(), item.getName())).toList();
        return new SkillOptionsRespDTO(categories, tags, SkillPackageLimitConstant.MAX_ARCHIVE_BYTES,
                SkillPackageLimitConstant.MAX_UNPACKED_BYTES, SkillPackageLimitConstant.MAX_FILE_COUNT,
                new com.yonagi.verse.dto.resp.SkillGithubLimitsRespDTO(
                        com.yonagi.verse.common.constant.SkillGithubLimitConstant.MAX_ARCHIVE_BYTES,
                        com.yonagi.verse.common.constant.SkillGithubLimitConstant.MAX_UNPACKED_BYTES,
                        com.yonagi.verse.common.constant.SkillGithubLimitConstant.MAX_ENTRIES,
                        com.yonagi.verse.common.constant.SkillGithubLimitConstant.MAX_CANDIDATES));
    }

    private SkillSearchCondition normalize(SkillSearchReqDTO input) {
        String keyword = blankToNull(input.getKeyword());
        String category = blankToNull(input.getCategory());
        String source = blankToNull(input.getSource());
        String sort = blankToNull(input.getSort());
        int page = input.getPage() == null ? 1 : input.getPage();
        int size = input.getSize() == null ? DEFAULT_PAGE_SIZE : input.getSize();
        List<String> tags = input.getTags() == null ? List.of() : input.getTags().stream().distinct().toList();
        if (page < 1 || page > MAX_PAGE || !PAGE_SIZES.contains(size) || tags.size() > MAX_TAGS
                || tags.stream().anyMatch(tag -> tag == null || tag.isBlank() || tag.length() > MAX_FILTER_LENGTH)
                || (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH)
                || (category != null && category.length() > MAX_FILTER_LENGTH)
                || (input.getTags() != null && input.getTags().size() > MAX_TAGS)
                || (sort != null && !SORTS.contains(sort))
                || (source != null && !Set.of(ZIP_SOURCE, "GITHUB").contains(source))
                || (input.getPublisherId() != null && input.getPublisherId() < 1)) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        String escaped = keyword == null ? null : keyword.replace("!", "!!").replace("%", "!%")
                .replace("_", "!_");
        return new SkillSearchCondition(keyword, keyword, escaped == null ? null : escaped + "%",
                escaped == null ? null : "%" + escaped + "%", category, tags, input.getPublisherId(),
                "relevance".equals(sort) || (sort == null && keyword != null), page, size, (page - 1) * size, source);
    }

    private Map<Long, List<String>> readTags(List<SkillPublicProjection> rows) {
        Map<Long, List<String>> result = new HashMap<>();
        if (rows.isEmpty()) {
            return result;
        }
        List<Long> revisions = rows.stream().map(SkillPublicProjection::getRevisionId).distinct().toList();
        for (SkillRevisionTagProjection tag : publicQueryMapper.selectRevisionTags(revisions)) {
            result.computeIfAbsent(tag.getRevisionId(), ignored -> new ArrayList<>()).add(tag.getTagId());
        }
        return result;
    }

    private SkillCardRespDTO card(SkillPublicProjection row, Map<Long, List<String>> tags) {
        SkillPublisherRespDTO publisher = new SkillPublisherRespDTO(row.getPublisherId().toString(),
                row.getHandle(), row.getPublicName());
        return new SkillCardRespDTO(row.getSkillId().toString(), publisher, row.getSlug(), row.getName(),
                row.getSummary(), row.getCategoryId(), List.copyOf(tags.getOrDefault(row.getRevisionId(), List.of())),
                row.getVersionLabel(), row.getVersionId().toString(), row.getPublishedAt().atOffset(ZoneOffset.UTC),
                row.getSourceType() == null ? "ZIP" : row.getSourceType());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
