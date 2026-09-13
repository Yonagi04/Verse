package com.yonagi.verse.common.security;

import com.yonagi.verse.common.convention.exception.ClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextInterceptorTest {

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void allowsCurrentTenantAndRejectsAnyOtherTargetWithSameErrorPath() throws Exception {
        TenantContextInterceptor interceptor = new TenantContextInterceptor(true);
        UserContextHolder.set(new UserContext().setCurrentTenantId(20L));

        assertTrue(interceptor.preHandle(request(Map.of("tenantId", "20")), response(), handler("normal")));
        assertThrows(ClientException.class,
                () -> interceptor.preHandle(request(Map.of("tenantId", "21")), response(), handler("normal")));
        assertThrows(ClientException.class,
                () -> interceptor.preHandle(request(Map.of("tenantId", "not-a-number")), response(), handler("normal")));
    }

    @Test
    void rejectsTenantPathWhenAuthenticatedContextHasNoTenant() throws Exception {
        UserContextHolder.set(new UserContext().setUserId(10L));
        TenantContextInterceptor interceptor = new TenantContextInterceptor(true);

        assertThrows(ClientException.class,
                () -> interceptor.preHandle(request(Map.of("tenantId", "20")), response(), handler("normal")));
    }

    @Test
    void naturallyAllowsRoutesWithoutTenantIdAndDisabledConfiguration() throws Exception {
        UserContextHolder.set(new UserContext().setCurrentTenantId(20L));

        assertTrue(new TenantContextInterceptor(true)
                .preHandle(request(Map.of("notificationId", "1")), response(), handler("normal")));
        assertTrue(new TenantContextInterceptor(false)
                .preHandle(request(Map.of("tenantId", "99")), response(), handler("normal")));
    }

    @Test
    void methodAndClassExceptionsRequireExplicitReason() throws Exception {
        UserContextHolder.set(new UserContext().setCurrentTenantId(20L));
        TenantContextInterceptor interceptor = new TenantContextInterceptor(true);

        assertTrue(interceptor.preHandle(request(Map.of("tenantId", "99")), response(), handler("exempt")));
        Method method = ExemptController.class.getDeclaredMethod("normal");
        assertTrue(interceptor.preHandle(request(Map.of("tenantId", "99")), response(),
                new HandlerMethod(new ExemptController(), method)));
    }

    private static MockHttpServletRequest request(Map<String, String> variables) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);
        return request;
    }

    private static MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    private static HandlerMethod handler(String name) throws Exception {
        Method method = PlainController.class.getDeclaredMethod(name);
        return new HandlerMethod(new PlainController(), method);
    }

    static class PlainController {
        void normal() {
        }

        @TenantContextExempt(reason = "测试控制面切换")
        void exempt() {
        }
    }

    @TenantContextExempt(reason = "测试类级控制面接口")
    static class ExemptController {
        void normal() {
        }
    }
}
