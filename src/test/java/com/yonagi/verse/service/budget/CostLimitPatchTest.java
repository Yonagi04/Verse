package com.yonagi.verse.service.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.dto.req.CostLimitPatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CostLimitPatchTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void missingAndNullAreDifferentAndMaxAmountIsExact() throws Exception {
        var absent = json.readValue("{}", CostLimitPatch.class);
        assertFalse(absent.isDailyPresent());
        var patch = json.readValue("{\"dailyLimitFen\":null,\"monthlyLimitFen\":\"99999999999999999999\",\"enabled\":false}", CostLimitPatch.class);
        assertTrue(patch.isDailyPresent()); assertNull(patch.getDailyLimitFen());
        assertFalse(patch.isWeeklyPresent()); assertEquals("99999999999999999999", patch.getMonthlyLimitFen());
        assertFalse(patch.getEnabled());
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "true", "[]", "{}", "\"\"", "\"0\"", "\"-1\"",
            "\"1e3\"", "\"1.0\"", "\"1000.1\"", "\"NaN\"", "\"Infinity\"", "\"100000000000000000000\""})
    void rejectsCoercionPrecisionAndOverflow(String value) {
        assertThrows(Exception.class, () -> json.readValue("{\"dailyLimitFen\":" + value + "}", CostLimitPatch.class));
    }
    @ParameterizedTest @ValueSource(strings = {"null", "\"true\"", "1", "{}"})
    void switchMustBeJsonBoolean(String value) {
        assertThrows(Exception.class, () -> json.readValue("{\"enabled\":" + value + "}", CostLimitPatch.class));
    }
}
