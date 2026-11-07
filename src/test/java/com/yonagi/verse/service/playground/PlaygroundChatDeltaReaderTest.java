package com.yonagi.verse.service.playground;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaygroundChatDeltaReaderTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  [DONE]  ", "null", "{}", "{\"usage\":{\"total_tokens\":3}}",
            "{\"choices\":null}", "{\"choices\":[]}", "{\"choices\":[null]}",
            "{\"choices\":[{\"delta\":null,\"finish_reason\":\"stop\"}]}",
            "{\"choices\":[{\"index\":0,\"finish_reason\":null,\"delta\":{\"role\":\"assistant\"}}]}"})
    void protocolMarkersAndMetadataDoNotCreateUserVisibleText(String frame) {
        assertNull(PlaygroundChatDeltaReader.text(frame));
    }

    @Test
    void readsOnlyTheFirstChoiceWithoutTrimmingText() {
        String frame = """
                {"choices":[{"delta":{"content":"  第一行\\n第二行"}},
                {"delta":{"tool_calls":[{"function":{"name":"private"}}]}}],"usage":{"total_tokens":3}}
                """;

        assertEquals("  第一行\n第二行", PlaygroundChatDeltaReader.text(frame));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"choices\":[{\"finish_reason\":\"tool_calls\"}]}",
            "{\"choices\":[{\"finish_reason\":\"function_call\",\"delta\":{\"content\":\"text\"}}]}",
            "{\"choices\":[{\"delta\":{\"function_call\":null}}]}",
            "{\"choices\":[{\"delta\":{\"content\":[{\"type\":\"text\",\"text\":\"structured\"}]}}]}"})
    void toolOrStructuredContentIsRejectedWithTheUnsupportedResponseCode(String frame) {
        assertError(frame, PlaygroundErrorCodeEnum.UNSUPPORTED_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}{}", "{\"choices\":{}}", "{\"choices\":[1]}",
            "{\"choices\":[{\"delta\":false}]}", "{\"choices\":[{\"finish_reason\":\"tool_calls\"}],\"error\":{}}",
            "{\"choices\":[{\"delta\":{\"content\":\"partial\"}}"})
    void invalidOrErrorFramesDoNotLeakPartialText(String frame) {
        assertError(frame, PlaygroundErrorCodeEnum.UPSTREAM_ERROR);
    }

    private static void assertError(String frame, PlaygroundErrorCodeEnum expected) {
        ClientException failure = assertThrows(ClientException.class, () -> PlaygroundChatDeltaReader.text(frame));
        assertEquals(expected.code(), failure.getErrorCode());
    }
}
