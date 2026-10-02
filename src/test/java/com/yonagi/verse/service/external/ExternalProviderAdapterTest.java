package com.yonagi.verse.service.external;

import com.yonagi.verse.common.config.ExternalAuthProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
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

class ExternalProviderAdapterTest {
    private ExternalProviderAdapter adapter() {
        var config=new ExternalAuthProperties(); config.setEnabled(true);
        for (String name:List.of("google","github","gitlab")) {
            var provider=new ExternalAuthProperties.Provider(); provider.setEnabled(true);
            provider.setClientId("test-client"); provider.setClientSecret("test-secret"); config.getProviders().put(name,provider);
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
