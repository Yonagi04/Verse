package com.yonagi.verse.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 白名单配置的规范化与模型级范围校验，不从供应商名猜参数能力。 */
public final class PlaygroundConfiguration {
    private PlaygroundConfiguration() { }

    public static JSONObject normalize(JSONObject input) {
        try { return normalizeObject(input == null ? null : JSON.parseObject(input.toJSONString())); }
        catch (RuntimeException ex) { throw invalid(); }
    }

    private static JSONObject normalizeObject(JSONObject input) {
        fields(input, "lanes", "synced", "presetId", "presetVersion");
        JSONArray lanes = input.getJSONArray("lanes");
        if (lanes == null || lanes.isEmpty() || lanes.size() > 3) throw invalid();
        Set<String> ids = new HashSet<>();
        JSONArray safe = new JSONArray();
        for (Object item : lanes) {
            if (!(item instanceof JSONObject lane)) throw invalid();
            fields(lane, "laneId", "serviceId", "config");
            String id = lane.getString("laneId");
            if (id == null) id = UUID.randomUUID().toString();
            try { if (id.length() != 36) throw invalid(); id = UUID.fromString(id).toString(); } catch (RuntimeException ex) { throw invalid(); }
            if (!ids.add(id) || !(lane.get("serviceId") instanceof String sid) || !sid.matches("[1-9][0-9]{0,18}")) throw invalid();
            try { Long.parseLong(sid); } catch (RuntimeException ex) { throw invalid(); }
            JSONObject config = lane.getJSONObject("config");
            if (config == null) config = new JSONObject();
            fields(config, "system", "temperature", "topP", "maxTokens");
            if (config.get("system") != null && !(config.get("system") instanceof String)) throw invalid();
            if (config.getString("system") != null && config.getString("system").length() > 100_000) throw invalid();
            for (String name : Set.of("temperature", "topP", "maxTokens")) {
                if (config.get(name) != null && !(config.get(name) instanceof Number)) throw invalid();
            }
            safe.add(JSONObject.of("laneId", id, "serviceId", sid, "config", JSON.parseObject(config.toJSONString())));
        }
        boolean synced = input.get("synced") == null || Boolean.TRUE.equals(input.get("synced"));
        if (input.get("synced") != null && !(input.get("synced") instanceof Boolean)) throw invalid();
        if (synced) for (int i = 1; i < safe.size(); i++) {
            if (!safe.getJSONObject(0).getJSONObject("config").equals(safe.getJSONObject(i).getJSONObject("config"))) throw invalid();
        }
        JSONObject result = JSONObject.of("lanes", safe, "synced", synced);
        if (input.get("presetId") instanceof String value) result.put("presetId", value);
        if (input.get("presetVersion") instanceof Number value) result.put("presetVersion", value);
        return result;
    }

    public static JSONObject capabilities(LlmServiceDO service, UpstreamProtocol protocol) {
        JSONObject settings = service.getProviderSettings() == null ? null : JSON.parseObject(service.getProviderSettings());
        JSONObject explicit = settings == null ? null : settings.getJSONObject("playground");
        JSONObject caps = JSONObject.of("system", explicit == null || !Boolean.FALSE.equals(explicit.get("system")));
        if (explicit == null) return caps;
        double adapterMax = protocol == UpstreamProtocol.ANTHROPIC_MESSAGES || protocol == UpstreamProtocol.BEDROCK_CONVERSE ? 1 : 2;
        for (String name : Set.of("temperature", "topP")) {
            JSONObject range = explicit.getJSONObject(name);
            if (range == null || !(range.get("min") instanceof Number) || !(range.get("max") instanceof Number)) continue;
            double min = Math.max(0, range.getDoubleValue("min"));
            double max = Math.min(name.equals("topP") ? 1 : adapterMax, range.getDoubleValue("max"));
            if (Double.isFinite(min) && Double.isFinite(max) && min <= max) caps.put(name, JSONObject.of("min", min, "max", max));
        }
        Number max = explicit.get("maxTokens") instanceof Number n ? n : null;
        if (max != null && max.doubleValue() == max.longValue() && max.longValue() > 0 && max.longValue() <= Integer.MAX_VALUE) {
            // PlayGround 与普通 API 转发使用独立输出限额，不受 maxOutputTokens 约束。
            caps.put("maxTokens", max.longValue());
        }
        return caps;
    }

    public static JSONObject parameters(JSONObject config, JSONObject caps) {
        JSONObject parameters = new JSONObject();
        if (!Boolean.TRUE.equals(caps.getBoolean("system")) && config.getString("system") != null && !config.getString("system").isBlank()) throw invalid();
        for (String name : Set.of("temperature", "topP")) {
            if (config.get(name) == null) continue;
            JSONObject range = caps.getJSONObject(name);
            double value = config.getDoubleValue(name);
            if (range == null || !Double.isFinite(value) || value < range.getDoubleValue("min") || value > range.getDoubleValue("max")) throw invalid();
            parameters.put(name.equals("topP") ? "top_p" : name, value);
        }
        if (config.get("maxTokens") != null) {
            double value = config.getDoubleValue("maxTokens");
            Long max = caps.getLong("maxTokens");
            if (max == null || !Double.isFinite(value) || value != Math.floor(value) || value < 1 || value > max) throw invalid();
            parameters.put("max_tokens", (long) value);
        } else if (caps.getLong("maxTokens") != null) {
            // 留空时使用 PlayGround 已配置上限，避免上游默认值绕过本入口限额。
            parameters.put("max_tokens", caps.getLong("maxTokens"));
        }
        return parameters;
    }

    public static void fields(JSONObject body, String... allowed) {
        if (body == null || !Set.of(allowed).containsAll(body.keySet())) throw invalid();
        for (String key : Set.of("title", "description", "prompt", "laneId", "attemptId")) {
            if (body.get(key) != null && !(body.get(key) instanceof String)) throw invalid();
        }
        for (String key : Set.of("revision", "version")) {
            if (body.containsKey(key) && (!(body.get(key) instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 1)) throw invalid();
        }
        if (body.containsKey("before") && !(body.get("before") instanceof Boolean)) throw invalid();
    }
    public static ClientException invalid() { return new ClientException(PlaygroundErrorCodeEnum.INVALID_CONFIG); }
}
