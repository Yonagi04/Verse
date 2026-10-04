package com.yonagi.verse.dto.req;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.CostBudgetErrorCodeEnum;
import lombok.Getter;
import java.math.BigDecimal;

/** 字段出现标记用于区分省略与显式清空；JSON 数字不能被强制转换成字符串。 */
@Getter
public class CostLimitPatch {
    public static final BigDecimal MAX_FEN = new BigDecimal("99999999999999999999");
    /** 成本开关；省略表示保持。 */
    private Boolean enabled;
    /** 日限额，整数分字符串。 */
    private String dailyLimitFen;
    /** 周限额，整数分字符串。 */
    private String weeklyLimitFen;
    /** 月限额，整数分字符串。 */
    private String monthlyLimitFen;
    /** 乐观锁配置版本。 */
    private String expectedVersion;
    /** 日字段是否出现。 */
    @JsonIgnore private boolean dailyPresent;
    /** 周字段是否出现。 */
    @JsonIgnore private boolean weeklyPresent;
    /** 月字段是否出现。 */
    @JsonIgnore private boolean monthlyPresent;

    @JsonSetter public void setEnabled(JsonNode value) {
        if (value == null || !value.isBoolean()) throw invalid();
        enabled = value.booleanValue();
    }
    @JsonSetter public void setDailyLimitFen(JsonNode value) { dailyPresent = true; dailyLimitFen = amount(value); }
    @JsonSetter public void setWeeklyLimitFen(JsonNode value) { weeklyPresent = true; weeklyLimitFen = amount(value); }
    @JsonSetter public void setMonthlyLimitFen(JsonNode value) { monthlyPresent = true; monthlyLimitFen = amount(value); }
    @JsonSetter public void setExpectedVersion(JsonNode value) {
        if (value == null || !value.isTextual() || !value.textValue().matches("[0-9]{1,19}")) throw invalid();
        try { Long.parseLong(value.textValue()); } catch (NumberFormatException e) { throw invalid(); }
        expectedVersion = value.textValue();
    }
    private String amount(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || !value.textValue().matches("[0-9]{1,20}")) throw invalid();
        BigDecimal amount = new BigDecimal(value.textValue());
        if (amount.signum() <= 0 || amount.compareTo(MAX_FEN) > 0) throw invalid();
        return amount.toPlainString();
    }
    private ClientException invalid() { return new ClientException(CostBudgetErrorCodeEnum.INVALID_CONFIG); }
}
