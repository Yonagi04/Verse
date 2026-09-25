package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;

import java.util.Set;

/** 向量输入和返回值的共同校验，确保维度及批量顺序与客户端请求一致。 */
final class EmbeddingPayload {
    private EmbeddingPayload() {}

    static int validate(JSONObject body) {
        if (!Set.of("model", "input", "dimensions", "encoding_format", "user").containsAll(body.keySet())) {
            throw unsupported();
        }
        Object input = body.get("input");
        int count;
        if (input instanceof String text && !text.isBlank()) {
            count = 1;
        } else if (input instanceof JSONArray values && !values.isEmpty()) {
            if (values.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank())) {
                throw unsupported();
            }
            count = values.size();
        } else {
            throw unsupported();
        }
        Integer dimensions;
        try { dimensions = body.getInteger("dimensions"); }
        catch (RuntimeException e) { throw unsupported(); }
        if (body.containsKey("dimensions") && (dimensions == null || dimensions <= 0)) throw unsupported();
        if (body.containsKey("encoding_format") && !"float".equals(body.getString("encoding_format"))) {
            throw unsupported();
        }
        return count;
    }

    static String normalize(String raw, int count, Integer dimensions) {
        JSONObject result;
        try { result = JSON.parseObject(raw); }
        catch (RuntimeException e) { throw UpstreamErrors.from(502, null); }
        if (result == null) throw UpstreamErrors.from(502, null);
        JSONArray data = result.getJSONArray("data");
        if (data == null || data.size() != count) throw UpstreamErrors.from(502, null);
        JSONObject[] ordered = new JSONObject[count];
        for (Object value : data) {
            if (!(value instanceof JSONObject item)) throw UpstreamErrors.from(502, null);
            Integer index = item.getInteger("index");
            JSONArray vector = item.getJSONArray("embedding");
            if (index == null || index < 0 || index >= count || ordered[index] != null || vector == null
                    || vector.isEmpty() || (dimensions != null && vector.size() != dimensions)
                    || vector.stream().anyMatch(component -> !(component instanceof Number))) {
                throw UpstreamErrors.from(502, null);
            }
            ordered[index] = item;
        }
        JSONArray sorted = new JSONArray();
        for (JSONObject item : ordered) sorted.add(item);
        result.put("data", sorted);
        return JSON.toJSONString(result);
    }

    private static ClientException unsupported() {
        return new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
    }
}
