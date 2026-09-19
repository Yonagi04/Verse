package com.yonagi.verse.async.outbox;

import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import com.yonagi.verse.dao.mapper.TenantActivityLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/** 仅在动态事实不存在时恢复失败事件。 */
@Service @RequiredArgsConstructor
public class TenantActivityReplayService {
    private final DomainEventOutboxMapper outboxMapper; private final TenantActivityLogMapper activityMapper;
    @Transactional(rollbackFor=Exception.class)
    public boolean replay(Long tenantId,String eventId){
        if(activityMapper.countByEventId(eventId)>0) return false;
        return outboxMapper.resetForReplay(tenantId,eventId,LocalDateTime.now())==1;
    }
}
