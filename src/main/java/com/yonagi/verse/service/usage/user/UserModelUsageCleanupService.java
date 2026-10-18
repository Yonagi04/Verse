package com.yonagi.verse.service.usage.user;

import com.yonagi.verse.dao.mapper.UserModelUsageCleanupMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 在账号注销持有用户资源锁时，分批清理用户模型调用读模型。 */
@Service @RequiredArgsConstructor
public class UserModelUsageCleanupService {
    private final UserModelUsageCleanupMapper cleanup;
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public boolean cleanBatch(Long userId) {
        int work=cleanup.work(userId), days=cleanup.daily(userId);
        return work==500 || days==500;
    }
}
