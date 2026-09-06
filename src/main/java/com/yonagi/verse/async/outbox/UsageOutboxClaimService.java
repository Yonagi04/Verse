package com.yonagi.verse.async.outbox;

import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.dao.entity.TokenUsageOutboxDO;
import com.yonagi.verse.dao.mapper.TokenUsageOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 通过行锁和租约原子声明待发送事件。 */
@Service
@RequiredArgsConstructor
public class UsageOutboxClaimService {
    private final TokenUsageOutboxMapper outboxMapper;
    private final UsageOutboxProperties properties;

    @Transactional(rollbackFor = Exception.class)
    public List<TokenUsageOutboxDO> claim(String owner) {
        LocalDateTime now = LocalDateTime.now();
        List<TokenUsageOutboxDO> rows = outboxMapper.lockClaimable(now, Math.max(1, properties.getBatchSize()));
        if (rows.isEmpty()) {
            return rows;
        }
        List<Long> ids = rows.stream().map(TokenUsageOutboxDO::getId).toList();
        LocalDateTime expiresAt = now.plusNanos(Math.max(1000, properties.getClaimLeaseMs()) * 1_000_000);
        int claimed = outboxMapper.claim(ids, owner, expiresAt, now);
        if (claimed != ids.size()) {
            throw new IllegalStateException("Outbox 声明数量不一致");
        }
        rows.forEach(row -> {
            row.setClaimOwner(owner);
            row.setClaimExpiresAt(expiresAt);
        });
        return rows;
    }
}
