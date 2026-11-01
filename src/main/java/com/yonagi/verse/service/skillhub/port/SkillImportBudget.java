package com.yonagi.verse.service.skillhub.port;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/** 将数据库执行截止与租约资格传递给网络、存储和解析边界。 */
public record SkillImportBudget(Instant deadline, BooleanSupplier eligible) {
    /** 已取消或无执行资格时立即退出；不能靠续租延长deadline。 */
    public void check() {
        if (Thread.currentThread().isInterrupted() || !eligible.getAsBoolean() || !deadline.isAfter(Instant.now())) {
            throw new SkillGithubSourceException("SOURCE_TIMEOUT", "处理已取消或超过执行期限", false, null);
        }
    }

    /** @return 当前外部调用最多可用的剩余时长 */
    public Duration remaining() {
        check();
        return Duration.between(Instant.now(), deadline);
    }
}
