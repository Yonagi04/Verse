package com.yonagi.verse.common.util;

import cn.hutool.http.useragent.UserAgentUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(OutputCaptureExtension.class)
class DeviceUtilTest {

    @Test
    void parseFailureReturnsFallbackWithoutLoggingUserAgent(CapturedOutput output) {
        String userAgent = "private-agent-fixture";
        try (MockedStatic<UserAgentUtil> parser = mockStatic(UserAgentUtil.class)) {
            parser.when(() -> UserAgentUtil.parse(userAgent)).thenThrow(new IllegalArgumentException(userAgent));

            assertThat(DeviceUtil.parseDeviceName(userAgent)).isEqualTo("未知设备");
        }

        assertThat(output.getAll()).contains("IllegalArgumentException").doesNotContain(userAgent);
    }
}
