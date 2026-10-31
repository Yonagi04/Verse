package com.yonagi.verse.adapter.skillhub.validation;

import org.junit.jupiter.api.Test;
import java.io.Reader;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 协作预算、解析输入检查和执行资格失效，不依赖真实计时或线程池。 */
class SkillValidationControlTest {
    @Test
    void expiresAtBudgetWithoutWaitingAndCannotBeRenewed() {
        AtomicLong time = new AtomicLong();
        SkillValidationControl control = new SkillValidationControl(() -> true, time::get);
        time.set(Duration.ofSeconds(120).minusNanos(1).toNanos());
        control.check();
        time.incrementAndGet();
        assertThrows(SkillValidationCancelledException.class, control::check);
    }

    @Test
    void cancellationSurvivesSubsequencesAndRegexBacktracking() {
        AtomicBoolean lease = new AtomicBoolean(true);
        SkillValidationControl control = new SkillValidationControl(lease::get);
        CharSequence text = control.watching("[".repeat(20_000));
        CharSequence nested = text.subSequence(1, text.length());
        assertEquals(text.length() - 1, nested.length());
        lease.set(false);
        assertThrows(SkillValidationCancelledException.class,
                () -> Pattern.compile("\\[[^]]*]").matcher(nested).find());
        assertThrows(SkillValidationCancelledException.class, nested::toString);
    }

    @Test
    void yamlReaderAndThreadInterruptionPreserveCancellation() throws Exception {
        AtomicBoolean lease = new AtomicBoolean(true);
        SkillValidationControl control = new SkillValidationControl(lease::get);
        try (Reader reader = control.reader("abc")) {
            assertEquals('a', reader.read());
            char[] buffer = new char[2];
            assertEquals(2, reader.read(buffer, 0, 2));
            lease.set(false);
            assertThrows(SkillValidationCancelledException.class, reader::read);
            assertThrows(SkillValidationCancelledException.class, () -> reader.read(buffer, 0, 2));
        }
        Thread.currentThread().interrupt();
        try {
            assertThrows(SkillValidationCancelledException.class,
                    () -> new SkillValidationControl(() -> true).check());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void revokedLeaseCannotBecomeYamlContentRejection() {
        AtomicLong checks = new AtomicLong();
        SkillValidationControl control = new SkillValidationControl(() -> checks.incrementAndGet() < 5);
        String markdown = "---\nname: test-skill\ndescription: test\nmetadata:\n  key: value\n---\n";
        assertThrows(SkillValidationCancelledException.class,
                () -> new SkillMarkdownPolicy(control).parse(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
    }
}
