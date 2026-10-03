package com.yonagi.verse.service.external;

import com.yonagi.verse.common.config.ExternalAuthProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import static com.yonagi.verse.common.enums.ExternalAuthErrorCodeEnum.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.endpoint.*;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** 固定可信端点的外部平台协议适配器，平台令牌仅存在于当前请求内。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExternalProviderAdapter {
    /** 支持的平台及展示顺序，登录和绑定管理共用。 */
    public static final List<String> SUPPORTED_PROVIDERS = List.of("google", "github", "gitlab", "feishu");
    private final ExternalAuthProperties properties;
    private final Map<String, JwtDecoder> decoders = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Instant> unavailableUntil = new java.util.concurrent.ConcurrentHashMap<>();

    public record VerifiedIdentity(String provider, String issuer, String subject, String username,
                                   String displayName, String verifiedEmail) { }

    public boolean enabled(String provider) {
        var config = properties.getProviders().get(provider);
        return SUPPORTED_PROVIDERS.contains(provider) && properties.isEnabled()
                && config != null && config.isEnabled() && config.getClientId() != null && !config.getClientId().isBlank()
                && config.getClientSecret() != null && !config.getClientSecret().isBlank()
                && (!"feishu".equals(provider) || config.getClientId().matches("cli_[A-Za-z0-9_-]{1,128}"));
    }
    public String availability(String provider) {
        if (!enabled(provider)) return "DISABLED";
        Instant until = unavailableUntil.get(provider);
        return until != null && until.isAfter(Instant.now()) ? "TEMPORARILY_UNAVAILABLE" : "AVAILABLE";
    }

    public ClientRegistration registration(String provider) {
        if (!enabled(provider)) throw new ClientException(PROVIDER_DISABLED);
        if (!"AVAILABLE".equals(availability(provider))) throw new ClientException(PROVIDER_UNAVAILABLE);
        var config = properties.getProviders().get(provider);
        String authorize, token, user, jwks = null, issuer;
        switch (provider) {
            case "google" -> {
                authorize = "https://accounts.google.com/o/oauth2/v2/auth";
                token = "https://oauth2.googleapis.com/token";
                user = "https://openidconnect.googleapis.com/v1/userinfo";
                jwks = "https://www.googleapis.com/oauth2/v3/certs"; issuer = "https://accounts.google.com";
            }
            case "github" -> {
                authorize = "https://github.com/login/oauth/authorize"; token = "https://github.com/login/oauth/access_token";
                user = "https://api.github.com/user"; issuer = "https://github.com";
            }
            case "gitlab" -> {
                authorize = "https://gitlab.com/oauth/authorize"; token = "https://gitlab.com/oauth/token";
                user = "https://gitlab.com/oauth/userinfo"; jwks = "https://gitlab.com/oauth/discovery/keys";
                issuer = "https://gitlab.com";
            }
            case "feishu" -> {
                authorize = "https://accounts.feishu.cn/open-apis/authen/v1/authorize";
                // 授权端点文档注明 PKCE 暂时配合 v2；v3 对有效 S256 请求仍有 20049 兼容问题。
                token = "https://open.feishu.cn/open-apis/authen/v2/oauth/token";
                user = "https://open.feishu.cn/open-apis/authen/v1/user_info";
                // open_id 仅在当前应用内唯一，更换 App ID 不能复用旧身份。
                issuer = "https://open.feishu.cn/apps/" + config.getClientId();
            }
            default -> throw new ClientException(PROVIDER_DISABLED);
        }
        var builder = ClientRegistration.withRegistrationId(provider).clientId(config.getClientId())
                .clientSecret(config.getClientSecret()).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(properties.getFrontendOrigin() + "/api/v1/auth/external/callback/" + provider)
                .authorizationUri(authorize).tokenUri(token).userInfoUri(user)
                .userNameAttributeName("github".equals(provider) ? "id" : "feishu".equals(provider) ? "open_id" : "sub")
                .scope("github".equals(provider) ? List.of("read:user", "user:email")
                        : "feishu".equals(provider) ? List.of() : List.of("openid", "profile", "email"));
        if (jwks != null) builder.jwkSetUri(jwks).issuerUri(issuer);
        if ("feishu".equals(provider)) builder.issuerUri(issuer);
        return builder.build();
    }

    public OAuth2AuthorizationRequest authorization(String provider, String state, String verifier, String nonce) {
        var client = registration(provider);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier)));
        params.put("code_challenge_method", "S256");
        if (Set.of("google", "gitlab").contains(provider)) params.put("nonce", nonce);
        // 飞书只支持 consent；不发送 OIDC nonce 或其他平台的账号选择参数。
        params.put("prompt", "feishu".equals(provider) ? "consent" : "gitlab".equals(provider) ? "login" : "select_account");
        return OAuth2AuthorizationRequest.authorizationCode().authorizationUri(client.getProviderDetails().getAuthorizationUri())
                .clientId(client.getClientId()).redirectUri(client.getRedirectUri()).scopes(client.getScopes()).state(state)
                .additionalParameters(params).attributes(Map.of("code_verifier", verifier)).build();
    }

    public VerifiedIdentity exchange(String provider, String code, String state, String verifier, String nonce) {
        String stage = "CLIENT_REGISTRATION";
        try {
            var client = registration(provider);
            RestTemplate http = http();
            DefaultAuthorizationCodeTokenResponseClient exchange = new DefaultAuthorizationCodeTokenResponseClient();
            if ("feishu".equals(provider)) {
                var requestConverter = new OAuth2AuthorizationCodeGrantRequestEntityConverter();
                exchange.setRequestEntityConverter(grant -> {
                    var formRequest = requestConverter.convert(grant);
                    @SuppressWarnings("unchecked")
                    var form = (org.springframework.util.MultiValueMap<String, String>) formRequest.getBody();
                    var jsonHeaders = new HttpHeaders();
                    jsonHeaders.putAll(formRequest.getHeaders());
                    jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
                    // 复用 Spring 的授权码参数，v2 按官方要求发送 JSON，保留原始 verifier。
                    return new RequestEntity<>(form.toSingleValueMap(), jsonHeaders, formRequest.getMethod(), formRequest.getUrl());
                });
            }
            var tokenConverter = new org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter();
            if ("feishu".equals(provider)) {
                var delegate = new DefaultMapOAuth2AccessTokenResponseConverter();
                // 飞书可能以 HTTP 200 返回业务错误，必须在标准 OAuth 解析前拒绝。
                tokenConverter.setAccessTokenResponseConverter(values -> {
                    requireFeishuSuccess(values, "TOKEN_EXCHANGE");
                    if (values.get("error") != null || !(values.get("access_token") instanceof String access) || access.isBlank()
                            || !(values.get("token_type") instanceof String type) || !"Bearer".equalsIgnoreCase(type)
                            || !(values.get("expires_in") instanceof Number expires) || expires.longValue() <= 0)
                        throw invalidIdentity("FEISHU_TOKEN_INVALID");
                    return delegate.convert(values);
                });
            }
            RestTemplate tokenHttp = new RestTemplate(List.of(new org.springframework.http.converter.FormHttpMessageConverter(),
                    tokenConverter, new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()));
            tokenHttp.setRequestFactory(http.getRequestFactory());
            var errorHandler = new org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler();
            if ("feishu".equals(provider)) {
                var errorConverter = new org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter();
                errorConverter.setErrorConverter(values -> {
                    logFeishuError(values, "TOKEN_EXCHANGE");
                    // 只保留标准错误类型，平台描述可能含敏感值，不传播到异常或日志。
                    String error = values.get("error");
                    String safeError = Set.of("invalid_request", "invalid_client", "invalid_grant", "unauthorized_client",
                            "unsupported_grant_type", "invalid_scope", "server_error", "temporarily_unavailable")
                            .contains(error == null ? "" : error) ? error : "invalid_response";
                    return new OAuth2Error(safeError);
                });
                errorHandler.setErrorConverter(errorConverter);
            }
            tokenHttp.setErrorHandler(errorHandler);
            exchange.setRestOperations(tokenHttp);
            var auth = authorization(provider, state, verifier, nonce);
            var response = OAuth2AuthorizationResponse.success(code).redirectUri(client.getRedirectUri()).state(state).build();
            stage = "TOKEN_EXCHANGE";
            var tokens = exchange.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(client, new OAuth2AuthorizationExchange(auth, response)));
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tokens.getAccessToken().getTokenValue()); headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            stage = "USERINFO";
            Map<?, ?> info = http.exchange(client.getProviderDetails().getUserInfoEndpoint().getUri(), HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class).getBody();
            if (info == null) throw invalidIdentity("USERINFO_MISSING");
            if ("feishu".equals(provider)) {
                requireFeishuSuccess(info, "USERINFO");
                if (!(info.get("data") instanceof Map<?, ?> profile)) throw invalidIdentity("FEISHU_PROFILE_MISSING");
                Object id = profile.get("open_id");
                if (!(id instanceof String subject) || !subject.matches("[A-Za-z0-9_-]{1,255}"))
                    throw invalidIdentity("FEISHU_OPEN_ID_INVALID");
                // 官方说明邮箱可由管理员导入，未经用户实时验证，不用于注册预填或身份匹配。
                return new VerifiedIdentity(provider, client.getProviderDetails().getIssuerUri(), subject,
                        null, text(profile.get("name"), 255), null);
            }
            if ("github".equals(provider)) {
                Object id = info.get("id");
                if (!(id instanceof Number) || !id.toString().matches("[1-9][0-9]{0,18}")) throw new ClientException(PROVIDER_RESPONSE_INVALID);
                String email = null;
                List<?> emails = http.exchange("https://api.github.com/user/emails", HttpMethod.GET,
                        new HttpEntity<>(headers), List.class).getBody();
                if (emails != null) for (Object entry : emails) {
                    if (entry instanceof Map<?, ?> row && Boolean.TRUE.equals(row.get("primary")) && Boolean.TRUE.equals(row.get("verified"))) {
                        email = text(row.get("email"), 100); break;
                    }
                }
                return new VerifiedIdentity(provider, "https://github.com", id.toString(), text(info.get("login"), 255), text(info.get("name"), 255), email);
            }
            Object raw = tokens.getAdditionalParameters().get("id_token");
            stage = "ID_TOKEN_VALIDATION";
            if (!(raw instanceof String encoded)) throw invalidIdentity("ID_TOKEN_MISSING");
            Jwt jwt = decoders.computeIfAbsent(provider, key -> decoder(client)).decode(encoded);
            validateClaims(jwt, client.getClientId(), provider, nonce);
            String sub = jwt.getSubject();
            if (sub == null || !sub.matches("[\\x21-\\x7E]{1,255}") || !sub.equals(info.get("sub"))) throw invalidIdentity("SUBJECT_MISMATCH");
            String email = Boolean.TRUE.equals(info.get("email_verified")) ? text(info.get("email"), 100) : null;
            return new VerifiedIdentity(provider, client.getProviderDetails().getIssuerUri(), sub,
                    text(info.get("preferred_username"), 255), text(info.get("name"), 255), email);
        } catch (ClientException e) {
            log.warn("外部身份验证失败: provider={}, stage={}, errorCode={}", provider, stage, e.getErrorCode());
            throw e;
        } catch (OAuth2AuthorizationException e) {
            // 仅记录协议错误码和异常类型，禁止输出授权码、令牌、密钥及完整响应。
            log.warn("外部身份验证失败: provider={}, stage={}, oauthError={}, causeType={}",
                    provider, stage, e.getError().getErrorCode(), rootCauseType(e));
            throw protocolFailure(provider, e);
        } catch (JwtException e) {
            log.warn("外部身份验证失败: provider={}, stage={}, exceptionType={}, causeType={}",
                    provider, stage, e.getClass().getSimpleName(), rootCauseType(e));
            throw protocolFailure(provider, e);
        } catch (RuntimeException e) {
            log.warn("外部平台请求失败: provider={}, stage={}, exceptionType={}, causeType={}",
                    provider, stage, e.getClass().getSimpleName(), rootCauseType(e));
            if (isNetworkFailure(e))
                unavailableUntil.put(provider, Instant.now().plusSeconds(60));
            throw new ClientException(PROVIDER_UNAVAILABLE);
        }
    }

    private static void requireFeishuSuccess(Map<?, ?> response, String stage) {
        if (!(response.get("code") instanceof Number code) || !"0".equals(code.toString())) {
            logFeishuError(response, stage);
            throw invalidIdentity("FEISHU_BUSINESS_ERROR");
        }
    }

    private static void logFeishuError(Map<?, ?> response, String stage) {
        // 数字错误码用于区分 invalid_grant 的具体原因，拒绝记录原始响应及任意文本。
        Object raw = response.get("code");
        String code = raw instanceof Number || raw instanceof String ? raw.toString() : "";
        if (!code.matches("[0-9]{1,6}")) code = "UNKNOWN";
        String reason = switch (code) {
            case "20001" -> "MISSING_PARAMETERS";
            case "20002" -> "CLIENT_SECRET_INVALID";
            case "20003" -> "AUTHORIZATION_CODE_NOT_FOUND";
            case "20004" -> "AUTHORIZATION_CODE_EXPIRED";
            case "20009" -> "APP_NOT_INSTALLED";
            case "20010" -> "USER_OUTSIDE_APP_AVAILABILITY";
            case "20024" -> "CLIENT_ID_MISMATCH";
            case "20049" -> "PKCE_VERIFICATION_FAILED";
            case "20065" -> "AUTHORIZATION_CODE_ALREADY_USED";
            case "20069" -> "APP_DISABLED";
            case "20070" -> "MULTIPLE_CLIENT_AUTHENTICATION_METHODS";
            case "20071" -> "REDIRECT_URI_MISMATCH";
            default -> "OTHER_PROVIDER_ERROR";
        };
        log.warn("飞书接口拒绝请求: stage={}, providerCode={}, reason={}", stage, code, reason);
    }

    static void validateClaims(Jwt jwt, String clientId, String provider, String nonce) {
        String issuer = jwt.getClaimAsString("iss");
        boolean trusted = "google".equals(provider)
                ? Set.of("accounts.google.com", "https://accounts.google.com").contains(issuer == null ? "" : issuer)
                : "https://gitlab.com".equals(issuer);
        String azp = jwt.getClaimAsString("azp");
        if (!trusted) throw invalidIdentity("ISSUER_INVALID");
        if (!jwt.getAudience().contains(clientId)) throw invalidIdentity("AUDIENCE_MISMATCH");
        if ((jwt.getAudience().size() > 1 && azp == null) || (azp != null && !clientId.equals(azp)))
            throw invalidIdentity("AUTHORIZED_PARTY_MISMATCH");
        if (!Objects.equals(nonce, jwt.getClaimAsString("nonce"))) throw invalidIdentity("NONCE_MISMATCH");
        if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(Instant.now())) throw invalidIdentity("ID_TOKEN_EXPIRED");
        if (jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(60)))
            throw invalidIdentity("ISSUED_AT_INVALID");
    }

    private static ClientException invalidIdentity(String reason) {
        // 记录固定诊断标识，不记录身份字段的实际值。
        log.warn("外部身份字段校验失败: reason={}", reason);
        return new ClientException(PROVIDER_RESPONSE_INVALID);
    }

    private static String rootCauseType(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getClass().getSimpleName();
    }

    private ClientException protocolFailure(String provider, RuntimeException error) {
        // Spring 会将令牌交换超时包装为 OAuth 异常，需检查原因链，避免误报身份校验失败。
        if (isNetworkFailure(error)) {
            unavailableUntil.put(provider, Instant.now().plusSeconds(60));
            return new ClientException(PROVIDER_UNAVAILABLE);
        }
        return new ClientException(PROVIDER_RESPONSE_INVALID);
    }

    private static boolean isNetworkFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.web.client.ResourceAccessException
                    || cause instanceof org.springframework.web.client.HttpServerErrorException
                    || cause instanceof SocketTimeoutException) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    private JwtDecoder decoder(ClientRegistration client) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(client.getProviderDetails().getJwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(http()).build();
        decoder.setJwtValidator(new JwtTimestampValidator(java.time.Duration.ofSeconds(60)));
        return decoder;
    }

    RestTemplate http() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMillis(properties.getConnectTimeout()));
        factory.setReadTimeout(timeoutMillis(properties.getReadTimeout()));
        // 令牌交换、用户资料及验签公钥请求共用代理；浏览器代理不会自动传给 Java 后端。
        String proxyUrl = properties.getProxyUrl();
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            URI uri = URI.create(proxyUrl.trim());
            Proxy.Type type = switch (Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT)) {
                case "http" -> Proxy.Type.HTTP;
                case "socks", "socks5" -> Proxy.Type.SOCKS;
                default -> throw new IllegalArgumentException("外部认证代理仅支持 HTTP 或 SOCKS");
            };
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))
                throw new IllegalArgumentException("外部认证代理需指定主机和端口，不能包含凭据或额外路径");
            factory.setProxy(new Proxy(type, new InetSocketAddress(uri.getHost(), uri.getPort())));
        }
        return new RestTemplate(factory);
    }

    private static int timeoutMillis(Duration timeout) {
        int millis = Math.toIntExact(timeout.toMillis());
        if (millis <= 0) throw new IllegalArgumentException("外部认证超时必须为正毫秒数");
        return millis;
    }
    static byte[] sha256(String value) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String text(Object value, int max) {
        if (!(value instanceof String s) || s.isBlank()) return null;
        s = s.replaceAll("[\\p{Cntrl}]", ""); return s.substring(0, Math.min(s.length(), max));
    }
}
