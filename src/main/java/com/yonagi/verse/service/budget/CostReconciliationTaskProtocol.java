package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.event.CostReconciliationTaskEvent;
import com.yonagi.verse.async.outbox.DomainEventPayload;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;

/** 冻结页面身份与成员摘要，拒绝空页、混合操作、重复成员和过大消息。 */
public final class CostReconciliationTaskProtocol {
    public static final int MAX_ITEMS = 100;
    public static final int MAX_BYTES = 16 * 1024;

    private CostReconciliationTaskProtocol() { }

    public static String hash(CostReconciliationTaskEvent event) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("schemaVersion", event.getSchemaVersion());
        fields.put("taskId", event.getTaskId());
        fields.put("runId", event.getRunId());
        fields.put("generation", event.getGeneration());
        fields.put("tenantId", event.getTenantId());
        fields.put("operation", event.getOperation());
        fields.put("requestIds", event.getRequestIds());
        fields.put("cases", event.getCases());
        return DomainEventPayload.hash(fields);
    }

    public static void validate(CostReconciliationTaskEvent event) {
        if (event == null || !Integer.valueOf(1).equals(event.getSchemaVersion())
                || event.getTaskId() == null || event.getTaskId().length() > 64
                || !Objects.equals(event.getTaskId(), event.getEventId())
                || event.getRunId() == null || event.getRunId() <= 0
                || event.getGeneration() == null || event.getGeneration() <= 0
                || event.getTenantId() == null || event.getTenantId() <= 0 || event.getOperation() == null) {
            throw new IllegalArgumentException("invalid reconciliation task envelope");
        }
        int size;
        if (event.getOperation() == CostReconciliationTaskEvent.Operation.CLASSIFY_REQUESTS) {
            if (event.getCases() != null || event.getRequestIds() == null
                    || event.getRequestIds().stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 64)) {
                throw new IllegalArgumentException("invalid reconciliation request members");
            }
            size = event.getRequestIds().size();
            if (new HashSet<>(event.getRequestIds()).size() != size) {
                throw new IllegalArgumentException("duplicate reconciliation request members");
            }
        } else {
            if (event.getRequestIds() != null || event.getCases() == null || event.getCases().stream().anyMatch(
                    row -> row == null || row.caseId() == null || row.caseId() <= 0
                            || row.version() == null || row.version() < 0)) {
                throw new IllegalArgumentException("invalid reconciliation case members");
            }
            size = event.getCases().size();
            if (event.getCases().stream().map(CostReconciliationTaskEvent.CaseVersion::caseId).distinct().count() != size) {
                throw new IllegalArgumentException("duplicate reconciliation case members");
            }
        }
        if (size < 1 || size > MAX_ITEMS || !Objects.equals(hash(event), event.getPayloadHash())
                || JSON.toJSONString(event).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("invalid reconciliation task size or hash");
        }
    }
}
