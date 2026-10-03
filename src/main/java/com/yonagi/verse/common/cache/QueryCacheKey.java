package com.yonagi.verse.common.cache;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

import java.lang.reflect.Array;
import java.util.*;

/** 参数规范化：Map 递归排序，列表保留业务顺序，集合按规范化内容排序。 */
final class QueryCacheKey {
    private QueryCacheKey() { }

    static String parametersJson(Object parameters) {
        return JSON.toJSONString(normalize(parameters), JSONWriter.Feature.WriteNulls);
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), normalize(item)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> values = new ArrayList<>();
            collection.forEach(item -> values.add(normalize(item)));
            // Set 不表达顺序，避免不同 JVM 的遍历顺序产生不同缓存键。
            if (value instanceof Set<?>) values.sort(Comparator.comparing(QueryCacheKey::parametersJson));
            return values;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) values.add(normalize(Array.get(value, i)));
            return values;
        }
        // DTO 转为 JSON 树后递归排序，保证嵌套 Map 与顶层 Map 使用相同规则。
        Object json = value == null ? null : JSON.parse(JSON.toJSONString(value, JSONWriter.Feature.WriteNulls));
        return json instanceof Map<?, ?> || json instanceof Collection<?> ? normalize(json) : json;
    }
}
