package com.yonagi.verse.service.external;

import com.yonagi.verse.common.config.ExternalAuthProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.security.*;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.service.*;
import com.yonagi.verse.service.impl.UserServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalAuthServiceTest {
    ExternalAuthFlowMapper flows=mock(ExternalAuthFlowMapper.class);
    ExternalIdentityMapper identities=mock(ExternalIdentityMapper.class);
    UserExternalBindingMapper bindings=mock(UserExternalBindingMapper.class);
    UserSecurityGuardMapper guards=mock(UserSecurityGuardMapper.class);
    UserMapper users=mock(UserMapper.class);
    StringRedisTemplate redis=mock(StringRedisTemplate.class);
    ValueOperations<String,String> values=mock(ValueOperations.class);
    ExternalProviderAdapter adapter=mock(ExternalProviderAdapter.class);
    UserLoginSessionService sessions=mock(UserLoginSessionService.class);
    UserServiceImpl registration=mock(UserServiceImpl.class);
    CurrentTenantStateService tenants=mock(CurrentTenantStateService.class);
    UserSecurityLocks locks=mock(UserSecurityLocks.class);
    ExternalAuthService service; ExternalAuthFlowDO flow; ExternalIdentityDO identity; MockHttpServletRequest request;
    @BeforeEach void setup() {
        var manager=mock(PlatformTransactionManager.class); when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service=new ExternalAuthService(new ExternalAuthProperties(),adapter,flows,identities,bindings,guards,users,redis,mock(AesUtil.class),
                mock(PasswordEncoder.class),mock(JwtUtil.class),sessions,registration,tenants,locks,manager);
        when(redis.opsForValue()).thenReturn(values); when(adapter.enabled(anyString())).thenReturn(true);
        when(locks.withUser(anyLong(),any())).thenAnswer(inv->((java.util.function.Supplier<?>)inv.getArgument(1)).get());
        flow=new ExternalAuthFlowDO(); flow.setFlowId("a".repeat(32)); flow.setPurpose("LOGIN"); flow.setStage("SELECT_REQUIRED"); flow.setProvider("google");
        flow.setFlowTokenHash(ExternalAuthService.hash("tab-proof")); flow.setBrowserCookieHash(ExternalAuthService.hash("browser-proof"));
        flow.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5)); flow.setExternalIdentityId(100L);
        flow.setIdentityBindingVersion(1L); flow.setCandidateSnapshot("{\"10\":20,\"11\":21}"); flow.setRegistrationCompleted(false);
        identity=new ExternalIdentityDO(); identity.setId(100L); identity.setProvider("google"); identity.setBindingVersion(1L);
        when(flows.lock(flow.getFlowId())).thenReturn(flow); when(identities.lock(100L)).thenReturn(identity);
        request=new MockHttpServletRequest(); request.setContentType("application/json"); request.addHeader("Origin","http://localhost:3000");
        request.addHeader("X-Requested-With","XMLHttpRequest"); request.addHeader("X-External-Flow-Token","tab-proof");
        request.setCookies(new Cookie("verse_ext_"+flow.getFlowId(),"browser-proof"));
    }
    UserExternalBindingDO relation(long id,long user) { var row=new UserExternalBindingDO(); row.setId(id); row.setUserId(user); row.setExternalIdentityId(100L); row.setProvider("google"); return row; }
    UserDO user(long id,int status) { var row=new UserDO(); row.setUserId(id); row.setUsername("user"+id); row.setNickname("昵称"); row.setStatus(status); return row; }
    @Test void browserAndTabProofsAreBothRequired() {
        request.removeHeader("X-External-Flow-Token"); assertEquals("A002101",assertThrows(ClientException.class,()->service.context(flow.getFlowId(),request)).getErrorCode());
        request.addHeader("X-External-Flow-Token","tab-proof"); request.setCookies(new Cookie("verse_ext_"+flow.getFlowId(),"different"));
        assertEquals("A002101",assertThrows(ClientException.class,()->service.context(flow.getFlowId(),request)).getErrorCode()); verifyNoInteractions(sessions,registration);
    }
    @Test void expiredProofCannotReadCandidates() {
        flow.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        assertEquals("A002100",assertThrows(ClientException.class,()->service.context(flow.getFlowId(),request)).getErrorCode());
    }
    @Test void removedCandidateStaysExplicitSelectionEvenWhenOneRemains() {
        identity.setBindingVersion(2L); when(bindings.selectList(any())).thenReturn(List.of(relation(20,10))); when(users.selectOne(any())).thenReturn(user(10,1));
        var result=service.context(flow.getFlowId(),request); assertEquals("SELECT_REQUIRED",result.stage()); assertEquals(1,result.accounts().size());
        assertEquals("FLOW_STATE_CHANGED",result.errorReason()); verifyNoInteractions(sessions);
    }
    @Test void disabledCandidatesStillAppearAndNeverBecomeRegistration() {
        when(bindings.selectList(any())).thenReturn(List.of(relation(20,10),relation(21,11))); when(users.selectOne(any())).thenReturn(user(10,0),user(11,0));
        var result=service.context(flow.getFlowId(),request); assertEquals("SELECT_REQUIRED",result.stage()); assertTrue(result.accounts().stream().allMatch(a->a.status().equals("DISABLED")));
        verifyNoInteractions(registration,sessions);
    }
    @Test void clientCannotChooseAnotherUser() {
        var input=new ExternalCompleteReqDTO(); input.setUserId("999");
        assertEquals("A002101",assertThrows(ClientException.class,()->service.complete(flow.getFlowId(),input,request)).getErrorCode()); verifyNoInteractions(sessions,tenants);
    }
    @Test void changingIdentityVersionPreventsSessionIssuance() {
        identity.setBindingVersion(2L); var input=new ExternalCompleteReqDTO(); input.setUserId("10");
        assertEquals("A002102",assertThrows(ClientException.class,()->service.complete(flow.getFlowId(),input,request)).getErrorCode()); verifyNoInteractions(sessions);
    }
    @Test void duplicateBindingSucceedsBeforeQuotaCheckButDifferentIdentityConflicts() {
        when(bindings.selectOne(any())).thenReturn(relation(20,10));
        var result=(UserExternalBindingDO)ReflectionTestUtils.invokeMethod(service,"bind",10L,identity,"flow"); assertEquals(20L,result.getId()); verify(bindings,never()).insert(any());
        var other=relation(20,10); other.setExternalIdentityId(999L); when(bindings.selectOne(any())).thenReturn(other);
        assertEquals("A002104",assertThrows(ClientException.class,()->ReflectionTestUtils.invokeMethod(service,"bind",10L,identity,"flow")).getErrorCode());
    }
    @Test void fourthRelationshipIsRejectedIncludingDisabledAccounts() {
        when(bindings.selectList(any())).thenReturn(List.of(relation(20,10),relation(21,11),relation(22,12)));
        assertEquals("A002103",assertThrows(ClientException.class,()->ReflectionTestUtils.invokeMethod(service,"bind",13L,identity,"flow")).getErrorCode()); verify(bindings,never()).insert(any());
    }
    @Test void alreadyRegisteredFlowCannotCreateAnotherAccountOrSession() {
        flow.setStage("COMPLETED"); flow.setRegistrationCompleted(true); flow.setSessionStatus("FAILED"); var result=service.register(flow.getFlowId(),new UserRegisterReqDTO(),request);
        assertEquals("REGISTERED_LOGIN_FAILED",result.outcome()); assertTrue(result.registrationCompleted()); assertNull(result.login()); verifyNoInteractions(registration,sessions);
    }
    @Test void callbackWithWrongBrowserOrReplayNeverExchangesCode() {
        when(flows.selectOne(any())).thenReturn(flow); request.addParameter("state","state"); request.setCookies(new Cookie("verse_ext_"+flow.getFlowId(),"wrong"));
        assertTrue(service.callback("google",request,new MockHttpServletResponse()).endsWith("/auth/external/error"));
        request.setCookies(new Cookie("verse_ext_"+flow.getFlowId(),"browser-proof")); when(flows.claim(any())).thenReturn(0);
        assertTrue(service.callback("google",request,new MockHttpServletResponse()).contains("#flow=")); verify(adapter,never()).exchange(any(),any(),any(),any(),any());
    }
    @Test void untrustedOriginIsRejectedBeforeMutation() {
        request.removeHeader("Origin"); request.addHeader("Origin","https://evil.example");
        assertEquals(403,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.source(request)).getStatusCode().value());
    }
}
