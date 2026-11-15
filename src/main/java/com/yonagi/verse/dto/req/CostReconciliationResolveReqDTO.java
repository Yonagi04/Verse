package com.yonagi.verse.dto.req;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.io.IOException;

public record CostReconciliationResolveReqDTO(
        /** 已核实的补账结论。 */ @NotNull Decision decision,
        /** 操作者已对照上游证据。 */ @NotNull Boolean evidenceConfirmed,
        /** 提交时的案件版本。 */ @NotNull @Min(0) @JsonDeserialize(using = IntegerNumber.class) Long version,
        /** 同一次操作重试时复用的标识。 */ @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String submissionKey,
        /** 上游输入 Token 数，包含缓存命中。 */ @Min(0) @Max(999_999_999_999L)
        @JsonDeserialize(using = IntegerNumber.class) Long inputTokens,
        /** 上游缓存命中输入 Token 数。 */ @Min(0) @Max(999_999_999_999L)
        @JsonDeserialize(using = IntegerNumber.class) Long cachedTokens,
        /** 上游输出 Token 数。 */ @Min(0) @Max(999_999_999_999L)
        @JsonDeserialize(using = IntegerNumber.class) Long outputTokens) {
    public enum Decision {
        CHARGE, NO_CHARGE
    }

    public static final class IntegerNumber extends StdDeserializer<Long> {
        public IntegerNumber() {
            super(Long.class);
        }

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            }
            return parser.getLongValue();
        }
    }
}
