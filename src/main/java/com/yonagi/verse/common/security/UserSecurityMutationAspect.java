package com.yonagi.verse.common.security;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 安全操作先取得分布式锁，再进入SQL事务，提交后才释放锁。 */
@Aspect
@Component
@Order(-100)
@RequiredArgsConstructor
public class UserSecurityMutationAspect {
    private final UserSecurityLocks locks;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;
    @Around("execution(* com.yonagi.verse.service.impl.UserServiceImpl.updatePassword(..)) || "
            + "execution(* com.yonagi.verse.service.impl.UserServiceImpl.confirmCloseAccount(..)) || "
            + "execution(* com.yonagi.verse.service.impl.UserServiceImpl.updateProfile(..)) || "
            + "execution(* com.yonagi.verse.service.impl.UserServiceImpl.logout(..)) || "
            + "execution(* com.yonagi.verse.service.impl.LoginDeviceServiceImpl.kickDevice(..))")
    public Object serialize(ProceedingJoinPoint point) throws Throwable {
        Throwable[] error = new Throwable[1];
        Object result = locks.withUser((Long) point.getArgs()[0], () -> {
            UserContext context = UserContextHolder.get();
            if (context != null && context.getTokenHash() != null && !context.getUserId().toString().equals(
                    redis.opsForValue().get(com.yonagi.verse.common.constant.RedisKeyConstant.USER_LOGIN_TOKEN_KEY + context.getTokenHash())))
                throw new com.yonagi.verse.common.convention.exception.ClientException(com.yonagi.verse.common.enums.ExternalAuthErrorCodeEnum.VERSE_SESSION_CHANGED);
            try { return point.proceed(); } catch (Throwable e) { error[0] = e; return null; }
        });
        if (error[0] != null) throw error[0];
        return result;
    }
}
