package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.mapper.skillhub.SkillPublicQueryMapper;
import com.yonagi.verse.dto.skillhub.SkillPublicationMetadataDTO;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 发布声明、版本与平台词表的业务规则，不承担JSON或HTTP职责。 */
@Component
@RequiredArgsConstructor
public class SkillPublicationPolicy {
    private static final Pattern SEMVER = Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
            + "(?:-(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
            + "(?:\\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*)?"
            + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    private final Validator validator;
    private final SkillPublicQueryMapper publicQueryMapper;

    /**
     * @param metadata 待保存的完整合法草稿字段
     * @throws ClientException 格式、版本或平台词表不合法
     */
    public void validateDraft(SkillPublicationMetadataDTO metadata) {
        if (metadata == null || !validator.validate(metadata).isEmpty()
                || !SEMVER.matcher(metadata.version()).matches()
                || metadata.tags().stream().distinct().count() != metadata.tags().size()) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        Set<String> categories = publicQueryMapper.selectCategories().stream()
                .map(item -> item.getId()).collect(Collectors.toSet());
        Set<String> tags = publicQueryMapper.selectTags().stream()
                .map(item -> item.getId()).collect(Collectors.toSet());
        if (!categories.contains(metadata.categoryId()) || !tags.containsAll(metadata.tags())) {
            throw new ClientException("请选择平台提供的分类与标签", SkillErrorCodeEnum.INVALID_INPUT);
        }
        if (metadata.sourceUrl() != null && !metadata.sourceUrl().isBlank()) {
            URI source;
            try {
                source = URI.create(metadata.sourceUrl());
            } catch (IllegalArgumentException exception) {
                throw new ClientException("来源地址无效", SkillErrorCodeEnum.INVALID_INPUT);
            }
            if (!("https".equalsIgnoreCase(source.getScheme()) || "http".equalsIgnoreCase(source.getScheme()))
                    || source.getHost() == null || source.getUserInfo() != null) {
                throw new ClientException("来源仅允许不含账号凭据的HTTP/HTTPS地址", SkillErrorCodeEnum.INVALID_INPUT);
            }
        }
    }

    /**
     * @param metadata 冻结前作者确认的声明
     * @throws ClientException 缺分发许可、授权确认或依赖声明
     */
    public void validateSubmission(SkillPublicationMetadataDTO metadata) {
        validateDraft(metadata);
        if (!Boolean.TRUE.equals(metadata.distributionAuthorized())
                || !Boolean.TRUE.equals(metadata.declarationsConfirmed())
                || metadata.license() == null || metadata.license().isBlank()) {
            throw new ClientException(SkillErrorCodeEnum.LICENSE_REQUIRED);
        }
    }
}
