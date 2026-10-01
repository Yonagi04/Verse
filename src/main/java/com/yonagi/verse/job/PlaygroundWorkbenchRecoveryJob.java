package com.yonagi.verse.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.dao.entity.PlaygroundAttemptDO;
import com.yonagi.verse.dao.mapper.PlaygroundAttemptMapper;
import com.yonagi.verse.service.impl.PlaygroundAttemptFinalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 回收未订阅、进程退出及连接中断遗留的二期尝试。 */
@Component
@RequiredArgsConstructor
public class PlaygroundWorkbenchRecoveryJob {
    private final PlaygroundAttemptMapper attemptMapper;
    private final PlaygroundAttemptFinalizer finalizer;

    @Scheduled(fixedDelayString = "${verse.playground.recovery-delay-ms:60000}")
    public void recover() {
        for (PlaygroundAttemptDO a : attemptMapper.selectList(Wrappers.lambdaQuery(PlaygroundAttemptDO.class)
                .in(PlaygroundAttemptDO::getStatus, "PENDING", "STREAMING", "STOPPING")
                .lt(PlaygroundAttemptDO::getUpdateTime, LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(5))
                .last("LIMIT 100"))) {
            finalizer.finish(a, a.getReply(), "STOPPED", a.getFirstContentMs(), a.getDurationMs(), null);
        }
    }
}
