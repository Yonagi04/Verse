package com.yonagi.verse.async.outbox;

import com.yonagi.verse.common.config.DomainOutboxProperties;
import com.yonagi.verse.dao.entity.DomainEventOutboxDO;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/** 使用行锁、实例 owner 与有限租约原子声明事件。 */
@Service
@RequiredArgsConstructor
public class DomainOutboxClaimService {
    private final DomainEventOutboxMapper mapper;
    private final DomainOutboxProperties properties;

    @Transactional(rollbackFor = Exception.class)
    public List<DomainEventOutboxDO> claim(String owner) {
        LocalDateTime now = LocalDateTime.now();
        List<DomainEventOutboxDO> rows = mapper.lockClaimable(now, Math.max(1, properties.getBatchSize()));
        if (rows.isEmpty()) return rows;
        List<Long> ids = rows.stream().map(DomainEventOutboxDO::getId).toList();
        LocalDateTime expires = now.plusNanos(Math.max(1000, properties.getClaimLeaseMs()) * 1_000_000);
        if (mapper.claim(ids, owner, expires, now) != ids.size()) throw new IllegalStateException("Outbox 声明数量不一致");
        rows.forEach(row -> { row.setClaimOwner(owner); row.setClaimExpiresAt(expires); });
        return rows;
    }
}
