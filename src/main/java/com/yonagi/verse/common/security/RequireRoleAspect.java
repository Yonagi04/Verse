package com.yonagi.verse.common.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

/**
 * 角色权限校验切面 — 拦截 @RequireRole 注解的方法，校验用户在该租户下的角色
 *
 * @author Yonagi
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RequireRoleAspect {

    private final UserTenantMapper userTenantMapper;

    @Around("@annotation(requireRole)")
    public Object checkRole(ProceedingJoinPoint joinPoint, RequireRole requireRole) throws Throwable {
        // 1. 检查登录状态
        UserContext ctx = UserContextHolder.get();
        if (ctx == null) {
            throw new ClientException(BaseErrorCode.TOKEN_MISSING);
        }

        // 2. 从方法参数中提取 tenantId
        Long tenantId = extractTenantId(joinPoint);
        if (tenantId == null) {
            throw new ServerException(
                    "@RequireRole 注解的方法缺少 @PathVariable(\"tenantId\") 参数，"
                    + "请确保 URL 路径包含 {tenantId}");
        }

        // 3. 查询用户在该租户下的角色
        UserTenantDO membership = userTenantMapper.selectOne(
                Wrappers.lambdaQuery(UserTenantDO.class)
                        .eq(UserTenantDO::getUserId, ctx.getUserId())
                        .eq(UserTenantDO::getTenantId, tenantId)
                        .isNull(UserTenantDO::getLeftAt)
        );

        if (membership == null) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }

        // 4. 校验角色
        RoleEnum userRole;
        try {
            userRole = RoleEnum.valueOf(membership.getRole());
        } catch (IllegalArgumentException e) {
            log.error("未知的角色类型: {}", membership.getRole());
            throw new ServerException("用户角色数据异常");
        }

        RoleEnum[] requiredRoles = requireRole.value();
        boolean hasPermission = Arrays.stream(requiredRoles).anyMatch(r -> r == userRole);

        if (!hasPermission) {
            log.warn("用户 {} 在租户 {} 中的角色 {} 无权限访问 {}, 需要角色: {}",
                    ctx.getUserId(), tenantId, userRole,
                    joinPoint.getSignature().toShortString(),
                    Arrays.toString(requiredRoles));
            throw new ClientException(TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        }

        return joinPoint.proceed();
    }

    /**
     * 从方法参数中提取 @PathVariable("tenantId") 的值
     */
    private Long extractTenantId(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length; i++) {
            PathVariable pathVariable = parameters[i].getAnnotation(PathVariable.class);
            if (pathVariable == null) {
                continue;
            }
            // 支持 @PathVariable("tenantId") 和 @PathVariable(name="tenantId")
            String pathVarName = pathVariable.value().isEmpty()
                    ? pathVariable.name().isEmpty()
                        ? parameters[i].getName()
                        : pathVariable.name()
                    : pathVariable.value();

            if ("tenantId".equals(pathVarName)) {
                Object arg = args[i];
                if (arg instanceof Long) {
                    return (Long) arg;
                }
                if (arg instanceof String) {
                    return Long.parseLong((String) arg);
                }
            }
        }
        return null;
    }
}
