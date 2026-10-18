package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.dao.mapper.UserCostAlertCleanupMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 在账号注销持有用户资源锁时，分批清理费用提醒及本人通知。 */
@Service @RequiredArgsConstructor
public class UserCostAlertCleanupService {
    private final UserCostAlertCleanupMapper cleanup;
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public boolean cleanBatch(Long userId) {
        int bodies=cleanup.deletePersonalNotifications(userId), recipients=cleanup.deletePersonalRecipients(userId);
        int deliveries=cleanup.deliveries(userId);
        if (bodies==500 || recipients==500 || deliveries==500) return true;
        if (cleanup.events(userId)==500) return true;
        cleanup.config(userId); return false;
    }
}
