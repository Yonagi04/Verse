package com.yonagi.verse.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.LlmManageErrorCodeEnum;
import com.yonagi.verse.dao.entity.LlmServiceTagDO;
import com.yonagi.verse.dao.entity.LlmTagDO;
import com.yonagi.verse.dao.mapper.LlmServiceTagMapper;
import com.yonagi.verse.dao.mapper.LlmTagMapper;
import com.yonagi.verse.dto.resp.TagInfoRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LlmMetadataService {

    private final LlmTagMapper tagMapper;
    private final LlmServiceTagMapper serviceTagMapper;

    public List<TagInfoRespDTO> catalogue() {
        return tagMapper.selectList(Wrappers.lambdaQuery(LlmTagDO.class)
                        .orderByAsc(LlmTagDO::getSortOrder))
                .stream()
                .map(tag -> {
                    TagInfoRespDTO dto = new TagInfoRespDTO();
                    dto.setCode(tag.getTagCode());
                    dto.setDisplayName(tag.getDisplayName());
                    dto.setDescription(tag.getDescription());
                    dto.setSortOrder(tag.getSortOrder());
                    return dto;
                })
                .toList();
    }

    public void replaceTags(Long serviceId, List<String> codes) {
        Set<String> requested = codes == null ? Set.of() : new LinkedHashSet<>(codes);
        if (requested.size() > 10 || requested.stream().anyMatch(code -> code == null || code.isBlank())) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_TAG_INVALID);
        }
        validateCodes(requested);
        serviceTagMapper.delete(Wrappers.lambdaQuery(LlmServiceTagDO.class)
                .eq(LlmServiceTagDO::getServiceId, serviceId));
        for (String code : requested) {
            LlmServiceTagDO relation = new LlmServiceTagDO();
            relation.setServiceId(serviceId);
            relation.setTagCode(code);
            serviceTagMapper.insert(relation);
        }
    }

    public void validateCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return;
        }
        Long count = tagMapper.selectCount(Wrappers.lambdaQuery(LlmTagDO.class)
                .in(LlmTagDO::getTagCode, codes));
        if (count == null || count != codes.stream().distinct().count()) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_TAG_INVALID);
        }
    }

    public Map<Long, List<String>> tagsByServiceIds(Collection<Long> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Map.of();
        }
        return serviceTagMapper.selectList(Wrappers.lambdaQuery(LlmServiceTagDO.class)
                        .in(LlmServiceTagDO::getServiceId, serviceIds))
                .stream()
                .collect(Collectors.groupingBy(
                        LlmServiceTagDO::getServiceId,
                        LinkedHashMap::new,
                        Collectors.mapping(LlmServiceTagDO::getTagCode, Collectors.toList())
                ));
    }

    public List<String> tags(Long serviceId) {
        return tagsByServiceIds(List.of(serviceId)).getOrDefault(serviceId, List.of());
    }
}
