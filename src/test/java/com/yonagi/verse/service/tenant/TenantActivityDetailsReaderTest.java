package com.yonagi.verse.service.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TenantActivityDetailsReaderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @MethodSource("projectedEvents")
    void returnsOnlyDetailsAllowedForThatEvent(String activityType, String snapshot, String expected)
            throws IOException {
        JsonNode actual = MAPPER.valueToTree(TenantActivityDetailsReader.read(activityType, snapshot));

        assertEquals(MAPPER.readTree(expected), actual);
    }

    private static Stream<Arguments> projectedEvents() {
        return Stream.of(
                Arguments.of("MEMBER_JOINED", "{\"joinSource\":\"APPROVAL\",\"newRole\":\"ADMIN\"}",
                        "{\"joinSource\":\"APPROVAL\"}"),
                Arguments.of("INVITE_ENABLED", """
                        {"status":"ENABLED","expiresAt":"2026-11-30 12:00:00","invitationCode":"private"}
                        """, "{\"status\":\"ENABLED\",\"expiresAt\":\"2026-11-30 12:00:00\"}"),
                Arguments.of("TENANT_COST_LIMIT_REACHED", """
                        {"period":"DAY","periodStart":"2026-11-07T00:00+08:00",
                        "periodEnd":"2026-11-08T00:00+08:00","triggeredAt":"2026-11-07T10:00+08:00",
                        "retryAt":"2026-11-08T00:00+08:00","usedCostFen":"1500","apiKeyId":"secret-id"}
                        """, """
                        {"period":"DAY","periodStart":"2026-11-07T00:00+08:00",
                        "periodEnd":"2026-11-08T00:00+08:00","triggeredAt":"2026-11-07T10:00+08:00",
                        "retryAt":"2026-11-08T00:00+08:00"}
                        """),
                Arguments.of("LLM_SERVICE_CREATED",
                        "{\"provider\":\"azure\",\"modelName\":\"model\",\"apiKey\":\"secret\"}",
                        "{\"provider\":\"azure\",\"modelName\":\"model\"}"),
                Arguments.of("LLM_SERVICE_UPDATED", """
                        {"changedFields":["name","limits"],"credentialChanged":true,"apiKey":"secret"}
                        """, "{\"changedFields\":[\"name\",\"limits\"],\"credentialChanged\":true}"),
                Arguments.of("LLM_SERVICE_UPDATED", "{\"changedFields\":[],\"credentialChanged\":false}",
                        "{\"changedFields\":[],\"credentialChanged\":false}"),
                Arguments.of("LLM_SERVICE_UPDATED", """
                        {"changedFields":["name",{"token":"secret"}],"credentialChanged":"false"}
                        """, "{}"),
                Arguments.of("LLM_SERVICE_UPDATED", "{\"changedFields\":\"name\",\"credentialChanged\":true}",
                        "{\"credentialChanged\":true}"),
                Arguments.of("TENANT_DISABLED", "{\"changedFields\":[\"name\"],\"status\":\"DISABLED\"}", "{}"),
                Arguments.of("FUTURE_EVENT", "{\"joinSource\":\"APPROVAL\"}", "{}"),
                Arguments.of(null, "{\"joinSource\":\"APPROVAL\"}", "{}"),
                Arguments.of("MEMBER_JOINED", null, "{}"),
                Arguments.of("MEMBER_JOINED", "  ", "{}"),
                Arguments.of("MEMBER_JOINED", "[{\"joinSource\":\"APPROVAL\"}]", "{}"),
                Arguments.of("MEMBER_JOINED", "{\"joinSource\":\"APPROVAL\"", "{}"),
                Arguments.of("MEMBER_JOINED", "{\"joinSource\":\"APPROVAL\"}{}", "{}"));
    }
}
