package com.yonagi.verse.adapter.skillhub.validation;

import java.io.Reader;
import java.io.StringReader;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** 每次解析独占的协作预算；不会把返回超时等同于底层计算已经退出。 */
final class SkillValidationControl {
    private static final long BUDGET_NANOS = Duration.ofSeconds(120).toNanos();
    private final BooleanSupplier eligible;
    private final LongSupplier clock;
    private final long started;
    private int accesses;

    SkillValidationControl(BooleanSupplier eligible) {
        this(eligible, System::nanoTime);
    }

    SkillValidationControl(BooleanSupplier eligible, LongSupplier clock) {
        this.eligible = eligible;
        this.clock = clock;
        this.started = clock.getAsLong();
    }

    void check() {
        if (Thread.currentThread().isInterrupted() || !eligible.getAsBoolean()
                || clock.getAsLong() - started >= BUDGET_NANOS) {
            throw new SkillValidationCancelledException();
        }
    }

    void accessed() {
        // 按访问次数检查，正则回溯反复访问同一字符也不能绕过预算。
        if ((++accesses & 1023) == 0) {
            check();
        }
    }

    CharSequence watching(CharSequence text) {
        check();
        return new CharSequence() {
            @Override
            public int length() {
                return text.length();
            }

            @Override
            public char charAt(int index) {
                accessed();
                return text.charAt(index);
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                return watching(text.subSequence(start, end));
            }

            @Override
            public String toString() {
                check();
                return text.toString();
            }
        };
    }

    Reader reader(String text) {
        return new StringReader(text) {
            @Override
            public int read(char[] buffer, int offset, int length) throws java.io.IOException {
                check();
                return super.read(buffer, offset, length);
            }

            @Override
            public int read() throws java.io.IOException {
                check();
                return super.read();
            }
        };
    }
}
