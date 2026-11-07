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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.*;
import org.springframework.mock.web.MockHttpServletRequest;
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
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    ExternalAuthService service; ExternalAuthFlowDO flow; ExternalIdentityDO identity; MockHttpServletRequest request;
    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
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
        assertTrue(service.callback("google",request,new RecordingCookies()).endsWith("/auth/external/error"));
        request.setCookies(new Cookie("verse_ext_"+flow.getFlowId(),"browser-proof")); when(flows.claim(any())).thenReturn(0);
        assertTrue(service.callback("google",request,new RecordingCookies()).contains("#flow=")); verify(adapter,never()).exchange(any(),any(),any(),any(),any());
    }
    @Test void untrustedOriginIsRejectedBeforeMutation() {
        request.removeHeader("Origin"); request.addHeader("Origin","https://evil.example");
        assertEquals(403,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.source(request)).getStatusCode().value());
    }
    @Test void feishuIsListedWhenEnabledAndHiddenWhenDisabled() {
        when(adapter.availability(anyString())).thenReturn("AVAILABLE");
        assertTrue(service.providers().stream().anyMatch(p->"feishu".equals(p.provider())));
        when(adapter.enabled("feishu")).thenReturn(false);
        assertFalse(service.providers().stream().anyMatch(p->"feishu".equals(p.provider())));
    }
    @Test void feishuLoginIssuesSessionWithFeishuSource() {
        flow.setProvider("feishu"); flow.setStage("LOGIN_READY"); flow.setCandidateSnapshot("{\"10\":20}");
        identity.setProvider("feishu");
        var user=user(10,1); var binding=relation(20,10); binding.setProvider("feishu");
        var tenant=new com.yonagi.verse.dao.projection.CurrentTenantState();
        when(guards.lockUser(10L)).thenReturn(user); when(bindings.selectById(20L)).thenReturn(binding);
        when(tenants.resolveCurrentTenant(10L)).thenReturn(tenant);
        var login=new com.yonagi.verse.dto.resp.UserLoginRespDTO().setUserId(10L).setToken("token");
        when(sessions.create(user,"FEISHU",request,tenant)).thenReturn(login);

        var result=service.complete(flow.getFlowId(),new ExternalCompleteReqDTO(),request);

        assertEquals("LOGGED_IN",result.outcome()); assertSame(login,result.login());
        verify(sessions).create(user,"FEISHU",request,tenant);
        assertEquals("COMPLETED",flow.getStage());
    }
    @Test void feishuCallbackRetainsStateBrowserAndReplayChecks() {
        flow.setProvider("feishu"); when(flows.selectOne(any())).thenReturn(flow);
        assertTrue(service.callback("feishu",request,new RecordingCookies()).endsWith("/auth/external/error"));
        request.addParameter("state","state"); request.setCookies(new Cookie("verse_ext_"+flow.getFlowId(),"wrong"));
        assertTrue(service.callback("feishu",request,new RecordingCookies()).endsWith("/auth/external/error"));
        request.setCookies(new Cookie("verse_ext_"+flow.getFlowId(),"browser-proof")); when(flows.claim(any())).thenReturn(0);
        assertTrue(service.callback("feishu",request,new RecordingCookies()).contains("#flow="));
        verify(adapter,never()).exchange(any(),any(),any(),any(),any());
    }
    @Test void feishuCallbackReusesRegistrationLoginSelectionAndBindingStages() {
        flow.setProvider("feishu"); identity.setProvider("feishu");
        when(flows.selectOne(any())).thenReturn(flow); when(flows.claim(any())).thenReturn(1);
        request.addParameter("state","state"); request.addParameter("code","feishu-code");
        when(values.getAndDelete(anyString())).thenReturn("encrypted-protocol");
        var aes=(AesUtil)ReflectionTestUtils.getField(service,"aes");
        when(aes.decrypt("encrypted-protocol")).thenReturn("{\"verifier\":\"verifier\",\"nonce\":\"nonce\"}");
        when(adapter.exchange("feishu","feishu-code","state","verifier","nonce"))
                .thenReturn(new ExternalProviderAdapter.VerifiedIdentity("feishu","https://open.feishu.cn/apps/cli_test","ou_stable",null,"飞书用户",null));
        when(identities.selectOne(any())).thenReturn(identity);
        for (int count=0;count<4;count++) {
            flow.setStage("AUTHENTICATING"); flow.setPurpose(count==3?"BIND":"LOGIN");
            when(bindings.selectList(any())).thenReturn(count==0?List.of():count==1?List.of(relation(20,10)):List.of(relation(20,10),relation(21,11)));
            var cookies = new RecordingCookies();
            String destination = service.callback("feishu", request, cookies);
            assertEquals(List.of(new CookieWrite("verse_ext_" + flow.getFlowId(), Duration.ofMinutes(5), true)),
                    cookies.writes);
            assertTrue(destination.contains("#flow="));
            assertEquals(List.of("REGISTER_REQUIRED","LOGIN_READY","SELECT_REQUIRED","BIND_CONFIRM_REQUIRED").get(count),flow.getStage());
            assertEquals("飞书用户",identity.getDisplayName()); assertFalse(identity.getEmailVerified()); assertNull(identity.getEmailEncrypted());
        }
        verify(identities,times(4)).ensure(argThat(row->"feishu".equals(row.getProvider())
                && "https://open.feishu.cn/apps/cli_test".equals(row.getIssuer()) && "ou_stable".equals(row.getSubject())));
        verifyNoInteractions(sessions,registration);
    }

    ExternalAuthFlowDO activeFlow(String id,String provider,String purpose,String stage) {
        var row=new ExternalAuthFlowDO(); row.setFlowId(id); row.setProvider(provider); row.setPurpose(purpose); row.setStage(stage);
        row.setBrowserCookieHash(ExternalAuthService.hash("browser-proof")); row.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        return row;
    }
    void prepareStart(List<ExternalAuthFlowDO> records) {
        when(flows.selectList(any())).thenReturn(records);
        when(redis.execute(any(org.springframework.data.redis.core.script.DefaultRedisScript.class),anyList(),anyString())).thenReturn(1L);
        when(adapter.authorization(anyString(),anyString(),anyString(),anyString())).thenReturn(
                org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri("https://accounts.feishu.cn/open-apis/authen/v1/authorize").clientId("cli_test")
                        .redirectUri("http://localhost:3000/api/v1/auth/external/callback/feishu").state("state").build());
    }
    ExternalFlowStartReqDTO startInput(String provider) { var input=new ExternalFlowStartReqDTO(); input.setProvider(provider); return input; }
    @Test void reportedThreeActiveFlowsRejectBindingWithSpecificLimitErrorAcrossProviders() {
        var a=activeFlow("a".repeat(32),"feishu","LOGIN","REGISTER_REQUIRED");
        var b=activeFlow("b".repeat(32),"github","BIND","AUTHORIZING");
        var c=activeFlow("c".repeat(32),"feishu","BIND","AUTHORIZING");
        var expired = activeFlow("d".repeat(32), "google", "LOGIN", "AUTHORIZING");
        expired.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        prepareStart(List.of(a,b,c,expired));
        request.setCookies(new Cookie("verse_ext_"+a.getFlowId(),"browser-proof"),new Cookie("verse_ext_"+b.getFlowId(),"browser-proof"),
                new Cookie("verse_ext_"+c.getFlowId(),"browser-proof"),
                new Cookie("verse_ext_" + expired.getFlowId(), "browser-proof"), new Cookie("verse_ext_legacy", "old"));
        for (String provider : List.of("feishu","github")) {
            var cookies = new RecordingCookies();
            ClientException failure = assertThrows(ClientException.class,
                    () -> service.start(startInput(provider), 10L, request, cookies));
            assertEquals("A002113", failure.getErrorCode());
            assertEquals(List.of(
                    new CookieWrite("verse_ext_legacy", Duration.ZERO, false),
                    new CookieWrite("verse_ext_" + expired.getFlowId(), Duration.ZERO, false)), cookies.writes);
        }
        verify(flows,never()).insert(any()); verifyNoInteractions(locks);
    }

    @Test
    void failedStartCommitStillEmitsCookieFromTransactionCallback() {
        prepareStart(List.of());
        request.setCookies();
        var failure = new TransactionSystemException("fixture commit failure");
        doThrow(failure).when(manager).commit(any());
        var cookies = new RecordingCookies();

        assertSame(failure, assertThrows(TransactionSystemException.class,
                () -> service.start(startInput("google"), null, request, cookies)));
        assertEquals(1, cookies.writes.size());
        assertEquals(Duration.ofMinutes(10), cookies.writes.getFirst().ttl());
        assertTrue(cookies.writes.getFirst().hasValue());
    }
    @Test void expiredMissingInvalidAndTerminalCookiesDoNotBlockANewFlowOrDestroyCompletionProof() {
        var expired=activeFlow("b".repeat(32),"github","BIND","AUTHORIZING"); expired.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        var cancelled=activeFlow("c".repeat(32),"feishu","BIND","CANCELLED");
        var completed=activeFlow("d".repeat(32),"github","LOGIN","COMPLETED");
        var failed=activeFlow("e".repeat(32),"feishu","LOGIN","FAILED");
        var invalid=activeFlow("f".repeat(32),"feishu","BIND","AUTHORIZING");
        prepareStart(List.of(expired,cancelled,completed,failed,invalid));
        request.setCookies(new Cookie("verse_ext_"+expired.getFlowId(),"browser-proof"),new Cookie("verse_ext_"+cancelled.getFlowId(),"browser-proof"),
                new Cookie("verse_ext_"+completed.getFlowId(),"browser-proof"),new Cookie("verse_ext_"+failed.getFlowId(),"browser-proof"),
                new Cookie("verse_ext_"+invalid.getFlowId(),"wrong-proof"),new Cookie("verse_ext_"+"0".repeat(32),"browser-proof"));
        var cookies = new RecordingCookies(); var result=service.start(startInput("feishu"),null,request,cookies);
        assertNotNull(result.flowId()); verify(flows).insert(any());
        assertEquals(List.of(
                new CookieWrite("verse_ext_" + expired.getFlowId(), Duration.ZERO, false),
                new CookieWrite("verse_ext_" + invalid.getFlowId(), Duration.ZERO, false),
                new CookieWrite("verse_ext_" + "0".repeat(32), Duration.ZERO, false),
                new CookieWrite("verse_ext_" + result.flowId(), Duration.ofMinutes(10), true)), cookies.writes);
    }
    @Test void duplicateFlowCookiesCountOnceAndOtherTabsFlowsAreNotCancelled() {
        var other=activeFlow("b".repeat(32),"github","BIND","AUTHORIZING"); prepareStart(List.of(flow,other));
        request.setCookies(new Cookie("verse_ext_"+flow.getFlowId(),"browser-proof"),new Cookie("verse_ext_"+flow.getFlowId(),"browser-proof"),
                new Cookie("verse_ext_"+other.getFlowId(),"browser-proof"));
        var cookies = new RecordingCookies(); service.start(startInput("github"),null,request,cookies);
        verify(flows,never()).updateById(any());
        assertEquals(1, cookies.writes.size());
        assertEquals(Duration.ofMinutes(10), cookies.writes.getFirst().ttl());
    }
    @Test void expiredFlowCanOnlyBeCancelledWithBothOriginalProofs() {
        flow.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)); var cookies = new RecordingCookies();
        request.removeHeader("X-External-Flow-Token");
        assertEquals("A002101",assertThrows(ClientException.class,()->service.cancel(flow.getFlowId(),request,cookies,false)).getErrorCode());
        request.addHeader("X-External-Flow-Token","tab-proof");
        request.setCookies(new Cookie("verse_ext_"+flow.getFlowId(),"wrong-proof"));
        assertEquals("A002101",assertThrows(ClientException.class,()->service.cancel(flow.getFlowId(),request,cookies,false)).getErrorCode());
        request.setCookies(new Cookie("verse_ext_"+flow.getFlowId(),"browser-proof"));
        assertEquals("A002100",assertThrows(ClientException.class,()->service.context(flow.getFlowId(),request)).getErrorCode());
        service.cancel(flow.getFlowId(),request,cookies,false);
        assertEquals("CANCELLED",flow.getStage()); assertFalse(ExternalAuthService.matches(flow.getFlowTokenHash(),"tab-proof"));
        assertEquals(List.of(new CookieWrite("verse_ext_" + flow.getFlowId(), Duration.ZERO, false)), cookies.writes);
        verify(redis).delete(List.of("verse:external:protocol:"+flow.getFlowId(),"verse:external:result:"+flow.getFlowId()));
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCallbackKeepsDiagnosticsForActionTtl(boolean redisUnavailable) {
        flow.setStage("AUTHENTICATING"); when(flows.selectOne(any())).thenReturn(flow); when(flows.claim(any())).thenReturn(1);
        request.addParameter("state","state"); request.addParameter("error","access_denied");
        var cookies = new RecordingCookies();
        if (redisUnavailable) {
            var failure = new DataAccessResourceFailureException("fixture Redis cleanup failure");
            when(redis.delete("verse:external:protocol:" + flow.getFlowId())).thenThrow(failure);
            assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                    () -> service.callback("google", request, cookies)));
        } else {
            assertTrue(service.callback("google", request, cookies).endsWith("#flow=" + flow.getFlowId()));
        }
        assertEquals("FAILED",flow.getStage()); assertEquals("AUTHORIZATION_CANCELLED",flow.getErrorReason());
        assertEquals(List.of(new CookieWrite("verse_ext_" + flow.getFlowId(), Duration.ofMinutes(5), true)),
                cookies.writes);
        assertEquals("FAILED",service.context(flow.getFlowId(),request).stage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelOrAcknowledgeClearsBrowserProofBeforeRedisFailure(boolean acknowledge) {
        if (acknowledge) {
            flow.setStage("COMPLETED");
        }
        var failure = new DataAccessResourceFailureException("fixture Redis cleanup failure");
        when(redis.delete(anyList())).thenThrow(failure);
        var cookies = new RecordingCookies();

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.cancel(flow.getFlowId(), request, cookies, acknowledge)));
        assertEquals(acknowledge ? "COMPLETED" : "CANCELLED", flow.getStage());
        assertEquals(List.of(new CookieWrite("verse_ext_" + flow.getFlowId(), Duration.ZERO, false)), cookies.writes);
        assertFalse(ExternalAuthService.matches(flow.getFlowTokenHash(), "tab-proof"));
        assertFalse(ExternalAuthService.matches(flow.getBrowserCookieHash(), "browser-proof"));
    }
    @Test void cancelledFlowIsNotResurrectedWhenProviderExchangeReturns() {
        flow.setStage("AUTHENTICATING"); flow.setProvider("feishu");
        when(flows.selectOne(any())).thenReturn(flow); when(flows.claim(any())).thenReturn(1);
        request.addParameter("state","state"); request.addParameter("code","feishu-code");
        when(values.getAndDelete(anyString())).thenReturn("encrypted-protocol");
        var aes=(AesUtil)ReflectionTestUtils.getField(service,"aes");
        when(aes.decrypt("encrypted-protocol")).thenReturn("{\"verifier\":\"verifier\",\"nonce\":\"nonce\"}");
        when(adapter.exchange(anyString(),anyString(),anyString(),anyString(),anyString())).thenAnswer(inv->{
            service.cancel(flow.getFlowId(),request,new RecordingCookies(),false);
            return new ExternalProviderAdapter.VerifiedIdentity("feishu","https://open.feishu.cn/apps/cli_test","ou_test",null,"用户",null);
        });
        var cookies = new RecordingCookies(); service.callback("feishu",request,cookies);
        assertEquals("CANCELLED",flow.getStage()); assertTrue(cookies.writes.isEmpty()); verify(identities,never()).ensure(any());
    }

    private record CookieWrite(String name, Duration ttl, boolean hasValue) {
    }

    private static final class RecordingCookies implements ExternalAuthCookieOutput {
        private final List<CookieWrite> writes = new ArrayList<>();

        @Override
        public void write(String name, String value, Duration ttl) {
            writes.add(new CookieWrite(name, ttl, !value.isEmpty()));
        }
    }
}
