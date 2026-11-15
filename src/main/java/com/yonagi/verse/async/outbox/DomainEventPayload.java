package com.yonagi.verse.async.outbox;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 不依赖数据库 JSON 对象键顺序的规范摘要。数组成员顺序保留。 */
public final class DomainEventPayload {
    private DomainEventPayload() { }

    public static String hash(Object payload) {
        Object tree = payload instanceof String json ? JSON.parse(json) : JSON.toJSON(payload);
        return DigestUtil.sha256Hex(JSON.toJSONString(canonical(tree)));
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((key, item) -> ordered.put(key.toString(), canonical(item)));
            return ordered;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DomainEventPayload::canonical).collect(Collectors.toList());
        }
        return value;
    }
}
