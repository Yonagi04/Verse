package com.yonagi.verse.service.external;

import com.yonagi.verse.common.config.ExternalAuthProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.time.Instant;
import java.net.SocketTimeoutException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(OutputCaptureExtension.class)
class ExternalProviderAdapterTest {
    private ExternalProviderAdapter adapter() {
        var config=new ExternalAuthProperties(); config.setEnabled(true);
        for (String name:ExternalProviderAdapter.SUPPORTED_PROVIDERS) {
            var provider=new ExternalAuthProperties.Provider(); provider.setEnabled(true);
            provider.setClientId("feishu".equals(name) ? "cli_test-client" : "test-client");
            provider.setClientSecret("test-secret"); config.getProviders().put(name,provider);
        }
        return new ExternalProviderAdapter(config);
    }
    @Test void allProvidersUsePkceAndOnlyIdentityScopes() {
        for (String name:List.of("google","github","gitlab")) {
            var request=adapter().authorization(name,"state","verifier","nonce");
            assertEquals("state",request.getState()); assertEquals("verifier",request.getAttributes().get("code_verifier"));
            assertEquals("S256",request.getAdditionalParameters().get("code_challenge_method"));
            assertEquals(43,request.getAdditionalParameters().get("code_challenge").toString().length());
            assertFalse(request.getScopes().contains("repo")); assertFalse(request.getScopes().contains("api"));
            assertEquals("gitlab".equals(name)?"login":"select_account",request.getAdditionalParameters().get("prompt"));
        }
    }
    @Test void missingConfigurationIsDisabled() { assertFalse(new ExternalProviderAdapter(new ExternalAuthProperties()).enabled("google")); }
    @Test void feishuUsesPkceV2AndConsentWithoutOidcOrExtraPermissions() {
        var adapter=adapter(); var client=adapter.registration("feishu");
        assertEquals("https://open.feishu.cn/open-apis/authen/v2/oauth/token",client.getProviderDetails().getTokenUri());
        var request=adapter.authorization("feishu","state","v".repeat(43),"nonce");
        assertEquals("https://accounts.feishu.cn/open-apis/authen/v1/authorize",request.getAuthorizationUri());
        assertEquals("http://localhost:3000/api/v1/auth/external/callback/feishu",request.getRedirectUri());
        assertEquals("state",request.getState()); assertEquals("consent",request.getAdditionalParameters().get("prompt"));
        assertEquals("S256",request.getAdditionalParameters().get("code_challenge_method"));
        assertFalse(request.getAdditionalParameters().containsKey("nonce")); assertTrue(request.getScopes().isEmpty());
    }
    @Test void feishuRequiresValidAppCredentialsAndUsesApplicationNamespace() {
        var config=new ExternalAuthProperties(); config.setEnabled(true);
        var provider=new ExternalAuthProperties.Provider(); provider.setEnabled(true); provider.setClientId("cli_first");
        config.getProviders().put("feishu",provider); var adapter=new ExternalProviderAdapter(config);
        assertFalse(adapter.enabled("feishu")); provider.setClientSecret("secret"); assertTrue(adapter.enabled("feishu"));
        String issuer=adapter.registration("feishu").getProviderDetails().getIssuerUri();
        provider.setClientId("cli_second"); assertNotEquals(issuer,adapter.registration("feishu").getProviderDetails().getIssuerUri());
        provider.setClientId("cli_bad/app"); assertFalse(adapter.enabled("feishu"));
        provider.setClientId("cli_first"); provider.setEnabled(false); assertFalse(adapter.enabled("feishu"));
        provider.setEnabled(true); config.setEnabled(false); assertFalse(adapter.enabled("feishu"));
    }
    private static final String FEISHU_TOKEN = "{\"code\":0,\"access_token\":\"platform-token\",\"token_type\":\"Bearer\",\"expires_in\":7200}";
    @Test void feishuErrorsKeepNumericDiagnosisWithoutLoggingSensitiveResponse(CapturedOutput output) {
        Map<Integer,String> reasons=Map.of(20003,"AUTHORIZATION_CODE_NOT_FOUND",20004,"AUTHORIZATION_CODE_EXPIRED",
                20024,"CLIENT_ID_MISMATCH",20049,"PKCE_VERIFICATION_FAILED",20065,"AUTHORIZATION_CODE_ALREADY_USED",
                20071,"REDIRECT_URI_MISMATCH");
        for (var entry:reasons.entrySet()) {
            for (HttpStatus status:List.of(HttpStatus.BAD_REQUEST,HttpStatus.OK)) {
                var adapter=spy(adapter()); var http=new RestTemplate(); doReturn(http).when(adapter).http();
                var server=MockRestServiceServer.bindTo(http).build();
                server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v2/oauth/token"))
                        .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
                                .body("{\"code\":"+entry.getKey()+",\"error\":\"invalid_grant\",\"error_description\":\"sensitive-description\",\"access_token\":\"sensitive-token\"}"));
                assertEquals("C002101",assertThrows(ClientException.class,
                        ()->adapter.exchange("feishu","sensitive-code","state","v".repeat(43),"nonce")).getErrorCode());
                assertTrue(output.getAll().contains("providerCode="+entry.getKey()+", reason="+entry.getValue()));
                server.verify();
            }
        }
        assertFalse(output.getAll().contains("sensitive-description"));
        assertFalse(output.getAll().contains("sensitive-token"));
        assertFalse(output.getAll().contains("sensitive-code"));
        assertFalse(output.getAll().contains("test-secret"));
    }
    @Test void feishuPkceMatchesKnownS256Vector() {
        var request=adapter().authorization("feishu","state","dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk","nonce");
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",request.getAdditionalParameters().get("code_challenge"));
        var query=org.springframework.web.util.UriComponentsBuilder.fromUriString(request.getAuthorizationRequestUri()).build().getQueryParams();
        assertEquals(List.of("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"),query.get("code_challenge"));
        assertEquals(List.of("S256"),query.get("code_challenge_method"));
        assertFalse(query.containsKey("code_verifier"));
    }
    @Test void feishuExchangesJsonWithPkceAndIgnoresImportedContactDetails() {
        var adapter=spy(adapter()); var http=new RestTemplate(); doReturn(http).when(adapter).http();
        var server=MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v2/oauth/token")).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(com.alibaba.fastjson2.JSON.toJSONString(Map.of(
                        "grant_type","authorization_code", "client_id","cli_test-client", "client_secret","test-secret",
                        "code","code", "code_verifier","v".repeat(43),
                        "redirect_uri","http://localhost:3000/api/v1/auth/external/callback/feishu")),true))
                .andRespond(withSuccess(FEISHU_TOKEN,MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v1/user_info"))
                .andExpect(header("Authorization","Bearer platform-token"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"open_id\":\"ou_stable\",\"union_id\":\"on_other\",\"user_id\":\"employee\",\"name\":\"飞书用户\",\"email\":\"imported@example.com\",\"email_verified\":true,\"enterprise_email\":\"work@example.com\"}}",MediaType.APPLICATION_JSON));
        var identity=adapter.exchange("feishu","code","state","v".repeat(43),"nonce");
        assertEquals("feishu",identity.provider()); assertEquals("ou_stable",identity.subject());
        assertEquals("https://open.feishu.cn/apps/cli_test-client",identity.issuer());
        assertEquals("飞书用户",identity.displayName()); assertNull(identity.username()); assertNull(identity.verifiedEmail()); server.verify();
    }
    @Test void feishuRejectsBusinessErrorsAndMalformedTokensBeforeFetchingProfile() {
        for (String response:List.of("{\"code\":20049,\"error\":\"invalid_grant\"}",
                "{\"code\":20049,\"access_token\":\"access\",\"token_type\":\"Bearer\",\"expires_in\":7200}",
                "{\"access_token\":\"access\",\"token_type\":\"Bearer\",\"expires_in\":7200}",
                "{\"code\":0,\"access_token\":\"\",\"token_type\":\"Bearer\",\"expires_in\":7200}",
                "{\"code\":0,\"access_token\":\"access\",\"token_type\":\"other\",\"expires_in\":7200}",
                "{\"code\":0,\"access_token\":\"access\",\"token_type\":\"Bearer\",\"expires_in\":0}")) {
            var adapter=spy(adapter()); var http=new RestTemplate(); doReturn(http).when(adapter).http();
            var server=MockRestServiceServer.bindTo(http).build();
            server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v2/oauth/token")).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
            assertEquals("C002101",assertThrows(ClientException.class,()->adapter.exchange("feishu","code","state","v".repeat(43),"nonce")).getErrorCode());
            assertEquals("AVAILABLE",adapter.availability("feishu")); server.verify();
        }
    }
    @Test void feishuRejectsInvalidProfilesRatherThanFallingBackToEmailOrUnionId() {
        for (String response:List.of("{\"code\":20021,\"data\":{\"open_id\":\"ou_stable\"}}",
                "{\"code\":0}","{\"code\":0,\"data\":{\"union_id\":\"on_stable\",\"email\":\"user@example.com\"}}",
                "{\"code\":0,\"data\":{\"open_id\":123}}", "{\"code\":0,\"data\":{\"open_id\":\" ou_stable\"}}",
                "{\"code\":0,\"data\":{\"open_id\":\""+"a".repeat(256)+"\"}}")) {
            var adapter=spy(adapter()); var http=new RestTemplate(); doReturn(http).when(adapter).http();
            var server=MockRestServiceServer.bindTo(http).build();
            server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v2/oauth/token")).andRespond(withSuccess(FEISHU_TOKEN,MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v1/user_info")).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
            assertEquals("C002101",assertThrows(ClientException.class,()->adapter.exchange("feishu","code","state","v".repeat(43),"nonce")).getErrorCode());
            server.verify();
        }
    }
    @Test void feishuHttpErrorsAndTimeoutRetainExistingAvailabilityPolicy() {
        for (HttpStatus status:List.of(HttpStatus.BAD_REQUEST,HttpStatus.SERVICE_UNAVAILABLE)) {
            var adapter=spy(adapter()); var http=new RestTemplate(); doReturn(http).when(adapter).http();
            var server=MockRestServiceServer.bindTo(http).build();
            server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v2/oauth/token"))
                    .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body("{\"code\":20049,\"error\":\"invalid_grant\"}"));
            assertEquals(status.is5xxServerError()?"C002100":"C002101",assertThrows(ClientException.class,
                    ()->adapter.exchange("feishu","code","state","v".repeat(43),"nonce")).getErrorCode());
            server.verify();
        }
        var adapter=spy(adapter()); var http=new RestTemplate(); doReturn(http).when(adapter).http();
        var server=MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v2/oauth/token")).andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertEquals("C002100",assertThrows(ClientException.class,
                ()->adapter.exchange("feishu","code","state","v".repeat(43),"nonce")).getErrorCode());
        assertEquals("TEMPORARILY_UNAVAILABLE",adapter.availability("feishu")); server.verify();
    }
    @Test void wrappedTokenTimeoutReportsUnavailableAndTemporarilyDisablesProvider() {
        var adapter=spy(adapter()); var http=new RestTemplate(); doReturn(http).when(adapter).http();
        var server=MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertEquals("C002100",assertThrows(ClientException.class,
                ()->adapter.exchange("google","code","state","verifier","nonce")).getErrorCode());
        assertEquals("TEMPORARILY_UNAVAILABLE",adapter.availability("google"));
        server.verify();
    }
    @Test void invalidClientResponseDoesNotMarkProviderAsNetworkUnavailable() {
        var adapter=spy(adapter()); var http=new RestTemplate(); doReturn(http).when(adapter).http();
        var server=MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\"}"));
        assertEquals("C002101",assertThrows(ClientException.class,
                ()->adapter.exchange("google","code","state","verifier","nonce")).getErrorCode());
        assertEquals("AVAILABLE",adapter.availability("google"));
        server.verify();
    }
    @Test void githubUsesDurableIdAndVerifiedPrimaryEmail() {
        var adapter=spy(adapter()); var http=new RestTemplate(); doReturn(http).when(adapter).http();
        var server=MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://github.com/login/oauth/access_token")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("code_verifier=verifier")))
                .andRespond(withSuccess("{\"access_token\":\"platform-token\",\"token_type\":\"bearer\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/user")).andExpect(header("Authorization","Bearer platform-token"))
                .andRespond(withSuccess("{\"id\":1234,\"login\":\"renamed\",\"name\":\"Person\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/user/emails")).andRespond(withSuccess("[{\"email\":\"fake@example.com\",\"verified\":false,\"primary\":true},{\"email\":\"real@example.com\",\"verified\":true,\"primary\":true}]",MediaType.APPLICATION_JSON));
        var identity=adapter.exchange("github","code","state","verifier","nonce");
        assertEquals("1234",identity.subject()); assertEquals("real@example.com",identity.verifiedEmail()); server.verify();
    }
    private Jwt claims(String issuer,String audience,String nonce,Instant expires) {
        return Jwt.withTokenValue("test").header("alg","RS256").issuer(issuer).subject("stable").audience(List.of(audience))
                .claim("nonce",nonce).issuedAt(Instant.now().minusSeconds(10)).expiresAt(expires).build();
    }
    @Test void oidcClaimsRejectWrongNonceAudienceIssuerAndExpiration() {
        assertDoesNotThrow(()->ExternalProviderAdapter.validateClaims(claims("accounts.google.com","test-client","nonce",Instant.now().plusSeconds(300)),"test-client","google","nonce"));
        for (Jwt invalid:List.of(claims("https://evil.example","test-client","nonce",Instant.now().plusSeconds(300)),
                claims("accounts.google.com","evil","nonce",Instant.now().plusSeconds(300)), claims("accounts.google.com","test-client","wrong",Instant.now().plusSeconds(300)),
                claims("accounts.google.com","test-client","nonce",Instant.now().minusSeconds(1))))
            assertEquals("C002101",assertThrows(ClientException.class,()->ExternalProviderAdapter.validateClaims(invalid,"test-client","google","nonce")).getErrorCode());
    }
    @Test void oidcVerifiesSignatureAndUserInfoSubject() throws Exception {
        for (boolean correctSignature:List.of(true,false)) {
            var key=new RSAKeyGenerator(2048).keyID("test").generate();
            var signer=correctSignature?key:new RSAKeyGenerator(2048).keyID("test").generate();
            var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),new JWTClaimsSet.Builder()
                    .issuer("https://accounts.google.com").subject("stable").audience("test-client").claim("nonce","nonce")
                    .issueTime(new Date(System.currentTimeMillis()-10000)).expirationTime(new Date(System.currentTimeMillis()+300000)).build());
            token.sign(new RSASSASigner(signer));
            var adapter=spy(adapter()); var http=new RestTemplate(); doReturn(http).when(adapter).http();
            var server=MockRestServiceServer.bindTo(http).build();
            server.expect(requestTo("https://oauth2.googleapis.com/token")).andRespond(withSuccess("{\"access_token\":\"access\",\"token_type\":\"bearer\",\"id_token\":\""+token.serialize()+"\"}",MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://openidconnect.googleapis.com/v1/userinfo")).andRespond(withSuccess("{\"sub\":\"stable\",\"name\":\"Person\"}",MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://www.googleapis.com/oauth2/v3/certs")).andRespond(withSuccess(com.alibaba.fastjson2.JSON.toJSONString(new JWKSet(key.toPublicJWK()).toJSONObject()),MediaType.APPLICATION_JSON));
            if (correctSignature) assertEquals("stable",adapter.exchange("google","code","state","verifier","nonce").subject());
            else assertEquals("C002101",assertThrows(ClientException.class,()->adapter.exchange("google","code","state","verifier","nonce")).getErrorCode());
            server.verify();
        }
    }
}
