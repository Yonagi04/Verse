package com.yonagi.verse.service.forward;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/** 终态类型和该帧正文一起发布，防止取消或损坏帧拼接出不存在的成功证据。 */
public final class ResponsesStreamAccumulator {
    private Snapshot terminal;

    public synchronized void accept(String event, String data) {
        if (terminal != null || !("response.completed".equals(event) || "response.failed".equals(event)
                || "response.incomplete".equals(event))) {
            return;
        }
        JSONObject response;
        try {
            JSONObject envelope = JSON.parseObject(data);
            response = envelope == null ? null : envelope.getJSONObject("response");
        } catch (RuntimeException malformed) {
            return;
        }
        if (response == null || response.isEmpty()) {
            return;
        }
        boolean completed = "response.completed".equals(event) && response.get("error") == null
                && (response.getString("status") == null || "completed".equals(response.getString("status")));
        String status = completed ? "SUCCESS" : "response.incomplete".equals(event) ? "ABORTED" : "FAIL";
        terminal = new Snapshot(response.toJSONString(), status, completed);
    }

    public synchronized Snapshot snapshot() {
        return terminal == null ? new Snapshot(null, "ABORTED", false) : terminal;
    }

    public record Snapshot(String responseJson, String status, boolean completed) {
        public JSONObject response() {
            return responseJson == null ? null : JSON.parseObject(responseJson);
        }
    }
}
