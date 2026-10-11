package com.yonagi.verse.dto.req;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.CostBudgetErrorCodeEnum;
import lombok.Getter;
import java.util.*;

/** 严格 JSON 类型的预警补丁；缺失保持，null 拒绝。 */
@Getter
public class CostWarningPatch {
    /** 保存的预警开关。 */ private Boolean enabled;
    /** 共用整数阈值。 */ private Integer thresholdPercent;
    /** 去重后的渠道，null 表示省略。 */ private List<String> channels;
    @JsonSetter public void setEnabled(JsonNode node) {
        if (node == null || !node.isBoolean()) throw invalid();
        enabled = node.booleanValue();
    }
    @JsonSetter public void setThresholdPercent(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()
                || node.intValue() < 50 || node.intValue() > 99) throw invalid();
        thresholdPercent = node.intValue();
    }
    @JsonSetter public void setChannels(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 20) throw invalid();
        Set<String> selected = new HashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || !List.of("IN_APP", "SMS", "EMAIL").contains(item.textValue())) throw invalid();
            selected.add(item.textValue());
        }
        channels = List.of("IN_APP", "SMS", "EMAIL").stream().filter(selected::contains).toList();
    }
    private ClientException invalid() { return new ClientException(CostBudgetErrorCodeEnum.WARNING_INVALID); }
}
