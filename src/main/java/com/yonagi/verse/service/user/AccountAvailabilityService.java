package com.yonagi.verse.service.user;

import com.yonagi.verse.dao.mapper.AccountAvailabilityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 跨领域只暴露账号有效性与显示名，不暴露账号实体及隐私。 */
@Service
@RequiredArgsConstructor
public class AccountAvailabilityService {
    private final AccountAvailabilityMapper accountAvailabilityMapper;

    /**
     * @param userId 可信账号
     * @return 现有账号显示名，账户不可用时null；不缓存可变的用户昵称
     */
    public String readActiveDisplayName(Long userId) {
        return accountAvailabilityMapper.selectActiveDisplayName(userId);
    }

    /**
     * 实时检查账号有效性，不解析或修复租户。
     *
     * @param userId 可信会话中的账号标识
     * @return 账号正常且未注销时为true
     */
    public boolean isAccountActive(Long userId) {
        return accountAvailabilityMapper.selectActiveUserId(userId) != null;
    }

    /**
     * 在调用者事务中检查账号并持有共享锁，阻止检查后发生并发注销。
     * 调用方必须先于Skill领域锁调用此方法。
     *
     * @param userId 可信会话或已验证资源归属中的账号标识
     * @return 当前读确认账号正常且未注销时为true
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean lockActiveAccount(Long userId) {
        return accountAvailabilityMapper.selectActiveUserIdForShare(userId) != null;
    }

    /**
     * 首次创建个人发布身份时按账号串行，避免不存在发布者行时依赖先查后插。
     *
     * @param userId 可信会话中的账号标识
     * @return 当前读确认正常未删除且已取得排他锁时为true
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean lockActiveAccountExclusively(Long userId) {
        return accountAvailabilityMapper.selectActiveUserIdForUpdate(userId) != null;
    }
}
