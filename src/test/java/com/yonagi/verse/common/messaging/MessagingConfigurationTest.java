package com.yonagi.verse.common.messaging;

import com.yonagi.verse.common.config.*;
import com.yonagi.verse.common.messaging.provider.*;
import com.yonagi.verse.common.convention.exception.MessageSendException;
import com.yonagi.verse.dto.req.SmsSendReqDTO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MessagingConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(MessagingConfiguration.class);
    @Test void disabledIsExplicitAndDevUsesIndependentTemplates() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertThrows(MessageSendException.class, () -> context.getBean(SmsProvider.class).validate(
                    new SmsSendReqDTO("r1", "TEST", null, null, "Verse", "13800138000", "10001", Map.of("code", "123456"))));
        });
        runner.withPropertyValues("verse.messaging.sms.provider=sms-dev", "verse.messaging.email.provider=mailpit",
                "verse.messaging.email.account-name=noreply@verse.test").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("sms-dev", context.getBean(SmsProvider.class).name());
            assertEquals("mailpit", context.getBean(EmailProvider.class).name());
        });
    }
    @Test void rejectsMissingCredentialsUnknownProviderInvalidTimeoutAndProdSimulator() {
        for (String[] config : new String[][]{
                {"verse.messaging.sms.provider=unknown"},
                {"verse.messaging.sms.provider=aliyun"},
                {"verse.messaging.read-timeout=0s"},
                {"verse.messaging.async.queue-capacity=-1"},
                {"verse.messaging.async.max-attempts=6"},
                {"verse.messaging.async.max-task-age=0s"},
                {"verse.messaging.email.provider=aliyun", "verse.messaging.email.account-name=sender@example.com"},
                {"spring.profiles.active=prod", "verse.messaging.sms.provider=sms-dev"},
                {"spring.profiles.active=prod", "verse.messaging.email.provider=mailpit", "verse.messaging.email.account-name=sender@example.com"}}) {
            runner.withPropertyValues(config).run(context -> assertNotNull(context.getStartupFailure()));
        }
    }
}
