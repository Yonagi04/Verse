package com.yonagi.verse.dto.req;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UserCostAlertErrorCode;

@JsonDeserialize(using=UserCostAlertSettingsReplaceReqDTO.Decoder.class)
public record UserCostAlertSettingsReplaceReqDTO(
        /** 正式配置版本，防止并发覆盖。 */
        @NotNull @Pattern(regexp = "0|[1-9][0-9]{0,18}") String expectedVersion,
        /** 每日独立设置。 */
        @NotNull @Valid PeriodConfig daily,
        /** 每周独立设置。 */
        @NotNull @Valid PeriodConfig weekly,
        /** 本人共用渠道，完整替换。 */
        @NotNull @Size(max = 3) List<@NotNull String> channels) {
    public record PeriodConfig(
            /** 本维度是否开启。 */
            @NotNull Boolean enabled,
            /** 绝对金额整数分，关闭可为空。 */
            @Pattern(regexp = "[1-9][0-9]{0,19}") String thresholdFen) { }

    /** 拒绝数字转字符串、字符串转布尔以及伪造接收人字段，保持精确协议。 */
    public static final class Decoder extends JsonDeserializer<UserCostAlertSettingsReplaceReqDTO> {
        @Override public UserCostAlertSettingsReplaceReqDTO deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            JsonNode root=parser.getCodec().readTree(parser);
            if (!fields(root,Set.of("expectedVersion","daily","weekly","channels")) || !root.path("expectedVersion").isTextual()
                    || !root.path("channels").isArray()) throw invalid();
            var channels=new java.util.ArrayList<String>();
            for (var channel:root.path("channels")) { if (!channel.isTextual()) throw invalid(); channels.add(channel.textValue()); }
            return new UserCostAlertSettingsReplaceReqDTO(root.path("expectedVersion").textValue(),period(root.path("daily")),period(root.path("weekly")),channels);
        }
        private PeriodConfig period(JsonNode node) {
            if (!node.isObject() || !node.path("enabled").isBoolean()) throw invalid();
            Set<String> names=new HashSet<>(); node.fieldNames().forEachRemaining(names::add);
            if (!Set.of("enabled","thresholdFen").containsAll(names)) throw invalid();
            var amount=node.get("thresholdFen");
            if (amount!=null && !amount.isNull() && !amount.isTextual()) throw invalid();
            return new PeriodConfig(node.path("enabled").booleanValue(),amount==null || amount.isNull() ? null : amount.textValue());
        }
        private boolean fields(JsonNode node,Set<String> expected) {
            if (!node.isObject()) return false;
            Set<String> names=new HashSet<>(); node.fieldNames().forEachRemaining(names::add); return expected.equals(names);
        }
        private ClientException invalid() { return new ClientException(UserCostAlertErrorCode.INVALID_SETTINGS); }
    }
}
