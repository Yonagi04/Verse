package com.yonagi.verse.service.usage.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.config.*;
import com.yonagi.verse.common.convention.exception.*;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 本人能力不查询租户成员关系；写入与注销共用资源行锁。 */
@Service @RequiredArgsConstructor
public class UserModelUsageAccessPolicy {
    private final UserMapper users;
    private final UserModelUsageProperties properties;
    private final UsageOutboxProperties outbox;

    public boolean collecting() { return properties.isProjectionEnabled() && outbox.isEnabled(); }
    public UserDO read(Long userId) {
        if (userId == null) throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        return valid(users.selectOne(Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getUserId,userId)));
    }
    public UserDO lock(Long userId) {
        if (userId == null) throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        return valid(users.selectActiveUserForUpdate(userId));
    }
    private UserDO valid(UserDO user) {
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()) || !Integer.valueOf(0).equals(user.getDelFlag()))
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        return user;
    }
}
