package com.yonagi.verse.service.skillhub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 固定形状私人快照的有界编解码，不接受任意JSON扩展字段。 */
@Component
@RequiredArgsConstructor
public class SkillGithubSnapshotCodec {
    private final ObjectMapper mapper;

    /** @return 有界JSON；超限是依赖/实现错误，不允许静默截断候选 */
    public String encode(Object value, int maximumBytes) {
        try {
            String json = mapper.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
                throw invalid();
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw invalid();
        }
    }

    /** @return 持久化的固定结构，缺失由调用者按阶段决定是否合法 */
    public <T> T decode(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(json);
        } catch (JsonProcessingException exception) {
            throw invalid();
        }
    }

    /** @return 缺失列表为明确空集合，非列表/未知字段拒绝 */
    public <T> List<T> list(String json, TypeReference<List<T>> type) {
        if (json == null) {
            return List.of();
        }
        try {
            List<T> values = mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(json);
            return List.copyOf(values);
        } catch (JsonProcessingException | NullPointerException exception) {
            throw invalid();
        }
    }

    /** @return 历史ZIP没有sourceJson时不冒充GitHub */
    public SkillSourceDTO source(String sourceType, String sourceJson) {
        if (!"GITHUB".equals(sourceType)) {
            return SkillSourceDTO.zip();
        }
        SkillSourceDTO source = decode(sourceJson, SkillSourceDTO.class);
        if (source == null || !"GITHUB".equals(source.type()) || source.commitSha() == null) {
            throw invalid();
        }
        return source;
    }

    private ServerException invalid() {
        return new ServerException("Skill来源快照不可读取或超过限制", SkillErrorCodeEnum.UNAVAILABLE);
    }
}
