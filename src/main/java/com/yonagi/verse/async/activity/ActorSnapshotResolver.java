package com.yonagi.verse.async.activity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 只解析 userId、username、nickname，不采集联系方式等敏感字段。 */
@Component
@RequiredArgsConstructor
public class ActorSnapshotResolver {
    private final UserMapper userMapper;

    public ActorSnapshot resolve(Long requestedUserId) {
        UserContext context = UserContextHolder.get();
        Long userId = requestedUserId != null ? requestedUserId : context == null ? null : context.getUserId();
        if (userId == null) throw new IllegalArgumentException("动态操作人不能为空");
        UserDO user = userMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                .select(UserDO::getUserId, UserDO::getUsername, UserDO::getNickname)
                .eq(UserDO::getUserId, userId));
        if (user == null) throw new IllegalArgumentException("动态操作人不存在: " + userId);
        String username = context != null && userId.equals(context.getUserId()) && context.getUsername() != null
                ? context.getUsername() : user.getUsername();
        return new ActorSnapshot(userId, username, user.getNickname());
    }
}
