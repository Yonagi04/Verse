package com.yonagi.verse.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.dao.entity.PlaygroundTurnDO;
import com.yonagi.verse.dao.mapper.PlaygroundTurnMapper;
import com.yonagi.verse.service.impl.PlaygroundTurnFinalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 进程退出或网络异常遗留的轮次最终收敛为停止状态。 */
@Component
@RequiredArgsConstructor
public class PlaygroundTurnRecoveryJob {
    private final PlaygroundTurnMapper turnMapper;
    private final PlaygroundTurnFinalizer finalizer;

    @Scheduled(fixedDelayString = "${verse.playground.recovery-delay-ms:60000}")
    public void recover() {
        LocalDateTime deadline = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(5);
        for (PlaygroundTurnDO turn : turnMapper.selectList(Wrappers.lambdaQuery(PlaygroundTurnDO.class)
                .in(PlaygroundTurnDO::getStatus, "PENDING", "STREAMING")
                .lt(PlaygroundTurnDO::getUpdateTime, deadline)
                .last("LIMIT 100"))) {
            finalizer.finish(turn.getTenantId(), turn.getOwnerUserId(), turn.getSessionId(),
                    turn.getTurnId(), turn.getReply(), "STOPPED");
        }
    }
}
