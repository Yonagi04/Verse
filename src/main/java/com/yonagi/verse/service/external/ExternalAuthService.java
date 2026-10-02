package com.yonagi.verse.service.external;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.config.ExternalAuthProperties;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.security.*;
import com.yonagi.verse.common.util.*;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.LoginSessionVO;
import com.yonagi.verse.dto.resp.ExternalAuthRespDTO.*;
import com.yonagi.verse.service.*;
import com.yonagi.verse.service.impl.UserServiceImpl;
import jakarta.servlet.http.*;
import org.springframework.http.ResponseCookie;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.yonagi.verse.common.enums.ExternalAuthErrorCodeEnum.*;

/** SQL保存业务阶段，Redis只保存有限期协议秘密和完成响应。 */
@Service
public class ExternalAuthService {
    private final ExternalAuthProperties config;
    private final ExternalProviderAdapter providers;
    private final ExternalAuthFlowMapper flows;
    private final ExternalIdentityMapper identities;
    private final UserExternalBindingMapper bindings;
    private final UserSecurityGuardMapper guards;
    private final UserMapper users;
    private final StringRedisTemplate redis;
    private final AesUtil aes;
    private final PasswordEncoder passwords;
    private final JwtUtil jwt;
    private final UserLoginSessionService sessions;
    private final UserServiceImpl registration;
    private final CurrentTenantStateService tenants;
    private final UserSecurityLocks locks;
    private final TransactionTemplate tx;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "verse:external:";

    public ExternalAuthService(ExternalAuthProperties config, ExternalProviderAdapter providers, ExternalAuthFlowMapper flows,
            ExternalIdentityMapper identities, UserExternalBindingMapper bindings, UserSecurityGuardMapper guards, UserMapper users,
            StringRedisTemplate redis, AesUtil aes, PasswordEncoder passwords, JwtUtil jwt, UserLoginSessionService sessions,
            UserServiceImpl registration, CurrentTenantStateService tenants, UserSecurityLocks locks, PlatformTransactionManager manager) {
        this.config=config; this.providers=providers; this.flows=flows; this.identities=identities; this.bindings=bindings;
        this.guards=guards; this.users=users; this.redis=redis; this.aes=aes; this.passwords=passwords; this.jwt=jwt;
        this.sessions=sessions; this.registration=registration; this.tenants=tenants; this.locks=locks;
        tx = new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<ProviderInfo> providers() {
        return List.of("google", "github", "gitlab").stream().filter(providers::enabled)
                .map(p -> new ProviderInfo(p, true, providers.availability(p))).toList();
    }

    public void source(HttpServletRequest request) {
        if (!config.getFrontendOrigin().equals(request.getHeader("Origin"))
                || request.getContentType() == null || !request.getContentType().startsWith("application/json")
                || !"XMLHttpRequest".equals(request.getHeader("X-Requested-With")))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "不可信请求来源");
    }

    private <T> T transaction(Supplier<T> action) { return tx.execute(status -> action.get()); }
    public Start start(ExternalFlowStartReqDTO input, Long user, HttpServletRequest request, HttpServletResponse response) {
        source(request);
        if (!providers.enabled(input.getProvider())) throw new ClientException(PROVIDER_DISABLED);
        rate("start:" + DigestUtil.sha256Hex(DeviceUtil.getClientIp(request)), config.getStartsPerMinute(), Duration.ofMinutes(1));
        if (request.getCookies() != null && Arrays.stream(request.getCookies()).filter(c -> c.getName().startsWith("verse_ext_")).count() >= 3)
            throw new ClientException(FLOW_IN_PROGRESS);
        if (user != null) return locks.withUser(user, () -> transaction(() -> startFlow(input, user, request, response)));
        return transaction(() -> startFlow(input, null, request, response));
    }

    private Start startFlow(ExternalFlowStartReqDTO input, Long user, HttpServletRequest request, HttpServletResponse response) {
        String id = id(); String state = secret(); String token = secret(); String cookie = secret();
        String verifier = secret(); String nonce = secret();
        ExternalAuthFlowDO flow = new ExternalAuthFlowDO();
        flow.setFlowId(id); flow.setProvider(input.getProvider()); flow.setPurpose(user == null ? "LOGIN" : "BIND");
        flow.setStage("AUTHORIZING"); flow.setStateHash(hash(state)); flow.setFlowTokenHash(hash(token)); flow.setBrowserCookieHash(hash(cookie));
        flow.setRegistrationCompleted(false); flow.setSessionStatus("NOT_STARTED"); flow.setCreateTime(now());
        flow.setExpiresAt(now().plus(config.getAuthTtl()));
        if (user != null) {
            Proof proof = consume(input.getReauthToken(), user, "BIND", input.getProvider(), null, null, request);
            capture(flow, proof, request);
        }
        String url = providers.authorization(input.getProvider(), state, verifier, nonce).getAuthorizationRequestUri();
        redis.opsForValue().set(PREFIX + "protocol:" + id, aes.encrypt(JSON.toJSONString(Map.of("verifier", verifier, "nonce", nonce))), config.getAuthTtl());
        flows.insert(flow); cookie(response, id, cookie, config.getAuthTtl().plus(config.getActionTtl()));
        return new Start(id, token, url, iso(flow.getExpiresAt()));
    }

    public String callback(String provider, HttpServletRequest request, HttpServletResponse response) {
        String state = request.getParameter("state");
        if (state == null || state.length() > 128) return config.getFrontendOrigin() + "/auth/external/error";
        ExternalAuthFlowDO flow = flows.selectOne(Wrappers.lambdaQuery(ExternalAuthFlowDO.class).eq(ExternalAuthFlowDO::getStateHash, hash(state)));
        if (flow == null || !flow.getProvider().equals(provider) || !matches(flow.getBrowserCookieHash(), cookieValue(request, flow.getFlowId())))
            return config.getFrontendOrigin() + "/auth/external/error";
        String destination = config.getFrontendOrigin() + "/auth/external/callback#flow=" + flow.getFlowId();
        if (!flow.getExpiresAt().isAfter(now())) { fail(flow.getFlowId(), "FLOW_EXPIRED"); return destination; }
        // CAS只领取一次，网络交换在事务外；回调重放不能再次消费授权码。
        if (flows.claim(flow.getFlowId()) != 1) return destination;
        try {
            if (request.getParameter("error") != null) {
                fail(flow.getFlowId(), "access_denied".equals(request.getParameter("error")) ? "AUTHORIZATION_CANCELLED" : "PROVIDER_RESPONSE_INVALID");
                return destination;
            }
            String code = request.getParameter("code");
            String protocol = redis.opsForValue().getAndDelete(PREFIX + "protocol:" + flow.getFlowId());
            if (code == null || code.length() > 2048 || protocol == null) throw new ClientException(FLOW_INVALID);
            var secrets = JSON.parseObject(aes.decrypt(protocol));
            var identity = providers.exchange(provider, code, state, secrets.getString("verifier"), secrets.getString("nonce"));
            transaction(() -> {
                var current = flows.lock(flow.getFlowId());
                if (!"AUTHENTICATING".equals(current.getStage()) || !current.getExpiresAt().isAfter(now())) throw new ClientException(FLOW_INVALID);
                ExternalIdentityDO row = new ExternalIdentityDO(); row.setId(SnowflakeIdUtil.nextId());
                row.setProvider(provider); row.setIssuer(identity.issuer()); row.setSubject(identity.subject()); identities.ensure(row);
                row = identities.selectOne(Wrappers.lambdaQuery(ExternalIdentityDO.class).eq(ExternalIdentityDO::getProvider, provider)
                        .eq(ExternalIdentityDO::getIssuer, identity.issuer()).eq(ExternalIdentityDO::getSubject, identity.subject()));
                row = identities.lock(row.getId());
                row.setExternalUsername(identity.username()); row.setDisplayName(identity.displayName());
                row.setEmailEncrypted(identity.verifiedEmail() == null ? null : aes.encrypt(identity.verifiedEmail()));
                row.setEmailVerified(identity.verifiedEmail() != null); row.setProfileUpdatedAt(now()); identities.updateById(row);
                current.setExternalIdentityId(row.getId()); current.setIdentityBindingVersion(row.getBindingVersion());
                var relations = relations(row.getId()); current.setCandidateSnapshot(snapshot(relations));
                current.setStage("BIND".equals(current.getPurpose()) ? "BIND_CONFIRM_REQUIRED"
                        : relations.isEmpty() ? "REGISTER_REQUIRED" : relations.size() == 1 ? "LOGIN_READY" : "SELECT_REQUIRED");
                current.setExpiresAt(now().plus(config.getActionTtl())); flows.updateById(current);
                return null;
            });
            cookie(response, flow.getFlowId(), cookieValue(request, flow.getFlowId()), config.getActionTtl());
        } catch (ClientException e) { fail(flow.getFlowId(), reason(e.getErrorCode()));
        } catch (RuntimeException e) { fail(flow.getFlowId(), "PROVIDER_UNAVAILABLE");
        } finally { redis.delete(PREFIX + "protocol:" + flow.getFlowId()); }
        return destination;
    }

    private String reason(String code) {
        return Arrays.stream(com.yonagi.verse.common.enums.ExternalAuthErrorCodeEnum.values())
                .filter(e -> e.code().equals(code)).map(Enum::name).findFirst().orElse("FLOW_INVALID");
    }
    private void fail(String id, String reason) {
        transaction(() -> { var row = flows.lock(id); if (row != null && !"COMPLETED".equals(row.getStage())) {
            row.setStage("FAILED"); row.setErrorReason(reason); row.setExpiresAt(now().plus(config.getActionTtl())); flows.updateById(row);
        } return null; });
    }

    public Context context(String id, HttpServletRequest request) {
        return transaction(() -> {
            var flow = proven(id, request); ExternalIdentityDO identity = flow.getExternalIdentityId() == null ? null : identities.lock(flow.getExternalIdentityId());
            List<UserExternalBindingDO> relations = identity == null ? List.of() : relations(identity.getId());
            if (identity != null && "REGISTER_REQUIRED".equals(flow.getStage()) && !identity.getBindingVersion().equals(flow.getIdentityBindingVersion())) {
                flow.setStage("INVALIDATED"); flow.setErrorReason("FLOW_STATE_CHANGED"); flows.updateById(flow);
            }
            if (identity != null && Set.of("LOGIN_READY", "SELECT_REQUIRED").contains(flow.getStage())
                    && (!identity.getBindingVersion().equals(flow.getIdentityBindingVersion()) || !snapshot(relations).equals(flow.getCandidateSnapshot()))) {
                flow.setStage("SELECT_REQUIRED"); flow.setCandidateSnapshot(snapshot(relations));
                flow.setIdentityBindingVersion(identity.getBindingVersion()); flow.setErrorReason("FLOW_STATE_CHANGED"); flows.updateById(flow);
            }
            List<Account> candidates = Set.of("LOGIN_READY", "SELECT_REQUIRED").contains(flow.getStage())
                    ? relations.stream().map(r -> account(user(r.getUserId()))).filter(Objects::nonNull).toList() : List.of();
            Defaults defaults = "REGISTER_REQUIRED".equals(flow.getStage()) && identity != null
                    ? new Defaults(identity.getDisplayName(), Boolean.TRUE.equals(identity.getEmailVerified()) ? decrypt(identity.getEmailEncrypted()) : null) : null;
            return new Context(id, flow.getPurpose(), flow.getStage(), flow.getProvider(), summary(identity), candidates, defaults,
                    flow.getTargetUserId() == null ? null : account(user(flow.getTargetUserId())), "BIND".equals(flow.getPurpose()) ? relations.size() : null,
                    iso(flow.getExpiresAt()), flow.getErrorReason(), "COMPLETED".equals(flow.getStage())
                    ? new CompletionSummary(Boolean.TRUE.equals(flow.getRegistrationCompleted()), flow.getSessionStatus()) : null);
        });
    }

    public LoginCompletion complete(String id, ExternalCompleteReqDTO input, HttpServletRequest request) {
        source(request);
        ExternalAuthFlowDO initial = transaction(() -> proven(id, request));
        if ("COMPLETED".equals(initial.getStage())) return restore(initial);
        if (!providers.enabled(initial.getProvider())) throw new ClientException(PROVIDER_DISABLED);
        if (!Set.of("LOGIN_READY", "SELECT_REQUIRED").contains(initial.getStage()) || !"LOGIN".equals(initial.getPurpose())) throw new ClientException(FLOW_INVALID);
        Map<String, Long> candidates = candidates(initial);
        String selected = "LOGIN_READY".equals(initial.getStage()) ? candidates.keySet().stream().findFirst().orElseThrow(() -> new ClientException(FLOW_STATE_CHANGED)) : input.getUserId();
        if (selected == null || !candidates.containsKey(selected)) throw new ClientException(FLOW_INVALID);
        Long userId = Long.valueOf(selected);
        // 提前解析租户，避免持有用户行锁后获取现有租户锁。
        var tenant = tenants.resolveCurrentTenant(userId);
        return locks.withUser(userId, () -> {
            com.yonagi.verse.dto.resp.UserLoginRespDTO[] issued = new com.yonagi.verse.dto.resp.UserLoginRespDTO[1];
            try {
                return transaction(() -> {
                    var flow = proven(id, request);
                    if ("COMPLETED".equals(flow.getStage())) return restore(flow);
                    if (!Set.of("LOGIN_READY", "SELECT_REQUIRED").contains(flow.getStage())) throw new ClientException(FLOW_INVALID);
                    var identity = identities.lock(flow.getExternalIdentityId()); checkVersion(flow, identity);
                    var user = guards.lockUser(userId);
                    if (user == null || Integer.valueOf(2).equals(user.getStatus())) throw new ClientException(FLOW_STATE_CHANGED);
                    if (!Integer.valueOf(1).equals(user.getStatus())) throw new ClientException(com.yonagi.verse.common.enums.UserErrorCodeEnum.USER_ACCOUNT_BANNED);
                    Long relationId = candidates(flow).get(selected);
                    var relation = relationId == null ? null : bindings.selectById(relationId);
                    if (relation == null || !relation.getUserId().equals(userId) || !relation.getExternalIdentityId().equals(identity.getId())) throw new ClientException(FLOW_STATE_CHANGED);
                    issued[0] = sessions.create(user, flow.getProvider().toUpperCase(Locale.ROOT), request, tenant);
                    LoginCompletion result = new LoginCompletion("LOGGED_IN", false, issued[0], "DASHBOARD");
                    cacheResult(flow, result); flow.setResultUserId(userId); flow.setSessionStatus("ISSUED"); finish(flow);
                    return result;
                });
            } catch (RuntimeException e) { if (issued[0] != null) cleanupSession(issued[0]); redis.delete(PREFIX + "result:" + id); throw e; }
        });
    }

    public LoginCompletion register(String id, UserRegisterReqDTO input, HttpServletRequest request) {
        source(request);
        boolean[] created = {false};
        var flow = transaction(() -> {
            var row = proven(id, request);
            if ("COMPLETED".equals(row.getStage())) return row;
            require(row, "LOGIN", "REGISTER_REQUIRED"); var identity = identities.lock(row.getExternalIdentityId()); checkVersion(row, identity);
            if (!providers.enabled(row.getProvider())) throw new ClientException(PROVIDER_DISABLED);
            if (!relations(identity.getId()).isEmpty()) throw new ClientException(FLOW_STATE_CHANGED);
            // 核心注册、个人租户、绑定、审计、Outbox及完成标记在同一个SQL事务提交。
            var user = registration.realRegister(input, aes.hashForLookup(input.getPhone()), aes.hashForLookup(input.getEmail()));
            var binding = bind(user.getUserId(), identity, row.getFlowId());
            row.setResultUserId(user.getUserId()); row.setResultBindingId(binding.getId()); row.setRegistrationCompleted(true);
            row.setSessionStatus("FAILED"); finish(row); created[0] = true; return row;
        });
        if (!Boolean.TRUE.equals(flow.getRegistrationCompleted())) throw new ClientException(FLOW_INVALID);
        // 已提交的注册不能再次创建；只有本次提交者可进行首次会话签发。
        if (!created[0]) return restore(flow);
        return locks.withUser(flow.getResultUserId(), () -> {
            com.yonagi.verse.dto.resp.UserLoginRespDTO[] login = {null};
            try {
                var tenant = tenants.resolveCurrentTenant(flow.getResultUserId());
                var result = transaction(() -> {
                    var current = proven(id, request); var identity = identities.lock(current.getExternalIdentityId());
                    var relation = bindings.selectById(current.getResultBindingId());
                    if (relation == null || !relation.getExternalIdentityId().equals(identity.getId())) throw new ClientException(FLOW_STATE_CHANGED);
                    var user = guards.lockUser(current.getResultUserId()); active(user);
                    var session = sessions.create(user, current.getProvider().toUpperCase(Locale.ROOT), request, tenant);
                    login[0] = session;
                    var completion = new LoginCompletion("LOGGED_IN", true, session, "DASHBOARD");
                    cacheResult(current, completion); current.setSessionStatus("ISSUED"); flows.updateById(current); return completion;
                });
                return result;
            } catch (RuntimeException e) {
                if (login[0] != null) cleanupSession(login[0]);
                redis.delete(PREFIX + "result:" + id);
                return new LoginCompletion("REGISTERED_LOGIN_FAILED", true, null, "REAUTHENTICATE");
            }
        });
    }

    public Context continueBinding(String id, HttpServletRequest request) {
        source(request); transaction(() -> { var row=proven(id, request); require(row,"LOGIN","REGISTER_REQUIRED");
            row.setStage("EXISTING_ACCOUNT_LOGIN"); flows.updateById(row); return null; });
        return context(id, request);
    }

    public Context attach(String id, Long user, ExternalAttachReqDTO input, HttpServletRequest request) {
        source(request); locks.withUser(user, () -> transaction(() -> {
            var row=proven(id,request); require(row,"LOGIN","EXISTING_ACCOUNT_LOGIN");
            Proof proof=consume(input.getReauthToken(),user,"ATTACH",row.getProvider(),null,id,request);
            capture(row,proof,request); row.setPurpose("BIND"); row.setStage("BIND_CONFIRM_REQUIRED"); flows.updateById(row); return null;
        })); return context(id,request);
    }

    public List<BindingInfo> list(Long user, HttpServletRequest request) {
        active(user(user)); currentSession(user, request);
        var relations=bindings.selectList(Wrappers.lambdaQuery(UserExternalBindingDO.class).eq(UserExternalBindingDO::getUserId,user));
        return List.of("google","github","gitlab").stream().map(provider -> {
            var bound=relations.stream().filter(r->r.getProvider().equals(provider)).findFirst().orElse(null);
            boolean enabled=providers.enabled(provider);
            if (!enabled && bound==null) return null;
            var identity=bound==null?null:identities.selectById(bound.getExternalIdentityId());
            Summary summary=summary(identity);
            Binding view=bound==null?null:new Binding(bound.getId().toString(),summary.displayName(),summary.username(),summary.maskedEmail(),iso(bound.getBoundAt()));
            String availability = providers.availability(provider);
            return new BindingInfo(provider,enabled,availability,view,"AVAILABLE".equals(availability)&&bound==null,bound!=null,
                    !enabled?"平台暂未启用，已有绑定仍保留":"TEMPORARILY_UNAVAILABLE".equals(availability)?"平台暂时不可用，请稍后绑定":null);
        }).filter(Objects::nonNull).toList();
    }

    private record Proof(Long user, String session, String tokenHash, Long version, String action, String provider,
                         String binding, String flow, LocalDateTime verifiedAt) { }

    public Reauth reauth(Long user, ExternalReauthReqDTO input, HttpServletRequest request) {
        source(request); rate("password:"+user, config.getPasswordAttempts(), Duration.ofMinutes(5));
        return locks.withUser(user, () -> transaction(() -> {
            ExternalAuthFlowDO existingFlow = input.getFlowId() != null && "BIND".equals(input.getAction()) ? proven(input.getFlowId(),request) : null;
            guards.ensure(user); Long version=guards.lock(user); var session=currentSession(user,request);
            var current=guards.lockUser(user); active(current);
            if (!passwords.matches(input.getPassword(),current.getPassword())) throw new ClientException(com.yonagi.verse.common.enums.UserErrorCodeEnum.PASSWORD_ERROR);
            if ("UNBIND".equals(input.getAction()) && input.getBindingId()==null) throw new ClientException(FLOW_INVALID);
            if (!"UNBIND".equals(input.getAction()) && !Set.of("google","github","gitlab").contains(Objects.toString(input.getProvider(),""))) throw new ClientException(FLOW_INVALID);
            Proof proof=new Proof(user,session.getSessionId(),hash(session.getToken()),version,input.getAction(),input.getProvider(),input.getBindingId(),input.getFlowId(),now());
            if (input.getFlowId()!=null && "BIND".equals(input.getAction())) {
                // 流程重新验密不延长授权期限，也不更换固定目标和会话。
                var flow=existingFlow; verifySession(flow,user,request,false);
                if (!"BIND_CONFIRM_REQUIRED".equals(flow.getStage()) || !flow.getProvider().equals(input.getProvider())) throw new ClientException(FLOW_INVALID);
                flow.setRecentVerifiedAt(now()); flows.updateById(flow);
            }
            String token=secret(); redis.opsForValue().set(PREFIX+"reauth:"+hash(token),JSON.toJSONString(proof),config.getRecentAuthTtl());
            return new Reauth(token,iso(now().plus(config.getRecentAuthTtl())));
        }));
    }

    private Proof consume(String token,Long user,String action,String provider,String binding,String flow,HttpServletRequest request) {
        if (token==null || token.length()>128) throw new ClientException(REAUTH_REQUIRED);
        String value=redis.opsForValue().getAndDelete(PREFIX+"reauth:"+hash(token));
        if (value==null) throw new ClientException(REAUTH_REQUIRED);
        Proof proof=JSON.parseObject(value,Proof.class); var session=currentSession(user,request);
        if (!user.equals(proof.user()) || !session.getSessionId().equals(proof.session()) || !hash(session.getToken()).equals(proof.tokenHash())
                || !Objects.equals(guards.version(user),proof.version()) || !action.equals(proof.action())
                || !Objects.equals(provider,proof.provider()) || !Objects.equals(binding,proof.binding()) || !Objects.equals(flow,proof.flow())
                || !proof.verifiedAt().plus(config.getRecentAuthTtl()).isAfter(now())) throw new ClientException(REAUTH_REQUIRED);
        return proof;
    }

    private void capture(ExternalAuthFlowDO row,Proof proof,HttpServletRequest request) {
        row.setTargetUserId(proof.user()); row.setTargetSessionId(proof.session()); row.setTargetTokenHash(proof.tokenHash());
        row.setSecurityVersion(proof.version()); row.setRecentVerifiedAt(proof.verifiedAt());
    }

    public BindingResult confirm(String id,Long user,HttpServletRequest request) {
        source(request); return locks.withUser(user,()->transaction(()->{
            var row=proven(id,request); guards.ensure(user); guards.lock(user); verifySession(row,user,request,true);
            if ("COMPLETED".equals(row.getStage()) && row.getResultBindingId()!=null) return new BindingResult(row.getResultBindingId().toString(),"ALREADY_BOUND",true);
            require(row,"BIND","BIND_CONFIRM_REQUIRED"); var identity=identities.lock(row.getExternalIdentityId());
            if (!providers.enabled(row.getProvider())) throw new ClientException(PROVIDER_DISABLED);
            active(guards.lockUser(user)); boolean already=bindings.selectCount(Wrappers.lambdaQuery(UserExternalBindingDO.class)
                    .eq(UserExternalBindingDO::getUserId,user).eq(UserExternalBindingDO::getExternalIdentityId,identity.getId()))>0;
            var relation=bind(user,identity,id); row.setResultBindingId(relation.getId()); finish(row);
            return new BindingResult(relation.getId().toString(),already?"ALREADY_BOUND":"BOUND",true);
        }));
    }

    public BindingResult unbind(Long user,String bindingId,ExternalUnbindReqDTO input,HttpServletRequest request) {
        source(request); return locks.withUser(user,()->transaction(()->{
            guards.ensure(user); guards.lock(user); currentSession(user,request); active(user(user));
            Long previous=guards.unbindResult(user,input.getOperationId());
            if (previous!=null) {
                if (!previous.toString().equals(bindingId)) throw new ClientException(ACTION_NOT_ALLOWED);
                return new BindingResult(bindingId,"ALREADY_UNBOUND",true);
            }
            var binding=bindings.selectById(Long.valueOf(bindingId));
            if (binding==null || !binding.getUserId().equals(user)) throw new ClientException(ACTION_NOT_ALLOWED);
            consume(input.getReauthToken(),user,"UNBIND",null,bindingId,null,request);
            var identity=identities.lock(binding.getExternalIdentityId()); var current=guards.lockUser(user); active(current);
            boolean password=current.getPassword()!=null && current.getPassword().matches("^\\$2[aby]\\$.*");
            boolean alternate=bindings.selectList(Wrappers.lambdaQuery(UserExternalBindingDO.class).eq(UserExternalBindingDO::getUserId,user))
                    .stream().anyMatch(r->!r.getId().equals(binding.getId()) && providers.enabled(r.getProvider()));
            if (!password && !alternate) throw new ClientException(LAST_LOGIN_METHOD);
            bindings.deleteById(binding.getId()); identity.setBindingVersion(identity.getBindingVersion()+1); identities.updateById(identity);
            guards.audit(id(),"UNBIND",binding.getProvider(),user,identity.getId(),binding.getId(),null,input.getOperationId(),"UNBOUND");
            return new BindingResult(bindingId,"UNBOUND",true);
        }));
    }

    private UserExternalBindingDO bind(Long user,ExternalIdentityDO identity,String flow) {
        var current=bindings.selectOne(Wrappers.lambdaQuery(UserExternalBindingDO.class).eq(UserExternalBindingDO::getUserId,user).eq(UserExternalBindingDO::getProvider,identity.getProvider()));
        if (current!=null) {
            if (current.getExternalIdentityId().equals(identity.getId())) return current;
            throw new ClientException(PROVIDER_ALREADY_BOUND);
        }
        if (relations(identity.getId()).size()>=3) throw new ClientException(EXTERNAL_QUOTA_EXCEEDED);
        var binding=new UserExternalBindingDO(); binding.setId(SnowflakeIdUtil.nextId()); binding.setUserId(user);
        binding.setExternalIdentityId(identity.getId()); binding.setProvider(identity.getProvider()); binding.setBoundAt(now()); bindings.insert(binding);
        identity.setBindingVersion(identity.getBindingVersion()+1); identities.updateById(identity);
        guards.audit(id(),"BIND",identity.getProvider(),user,identity.getId(),binding.getId(),flow,null,"BOUND"); return binding;
    }

    public void cancel(String id,HttpServletRequest request,HttpServletResponse response,boolean acknowledge) {
        source(request); transaction(()->{
            var row=proven(id,request);
            if (acknowledge && !"COMPLETED".equals(row.getStage())) throw new ClientException(FLOW_INVALID);
            if (!acknowledge && !"COMPLETED".equals(row.getStage())) { row.setStage("CANCELLED"); flows.updateById(row); }
            // 确认收到结果或取消后销毁SQL中的双重证明，旧请求不能继续恢复。
            row.setFlowTokenHash(hash(secret())); row.setBrowserCookieHash(hash(secret())); flows.updateById(row);
            return null;
        });
        cookie(response,id,"",Duration.ZERO); redis.delete(List.of(PREFIX+"protocol:"+id,PREFIX+"result:"+id));
    }

    private ExternalAuthFlowDO proven(String id,HttpServletRequest request) {
        if (id==null || !id.matches("[a-f0-9]{32}")) throw new ClientException(FLOW_INVALID);
        var flow=flows.lock(id);
        if (flow==null || !matches(flow.getFlowTokenHash(),request.getHeader("X-External-Flow-Token"))
                || !matches(flow.getBrowserCookieHash(),cookieValue(request,id))) throw new ClientException(FLOW_INVALID);
        if (!flow.getExpiresAt().isAfter(now())) throw new ClientException(FLOW_EXPIRED);
        return flow;
    }
    private void verifySession(ExternalAuthFlowDO flow,Long user,HttpServletRequest request,boolean recent) {
        var session=currentSession(user,request);
        if (!user.equals(flow.getTargetUserId()) || !session.getSessionId().equals(flow.getTargetSessionId())
                || !hash(session.getToken()).equals(flow.getTargetTokenHash()) || !Objects.equals(guards.version(user),flow.getSecurityVersion())) throw new ClientException(VERSE_SESSION_CHANGED);
        if (recent && (flow.getRecentVerifiedAt()==null || !flow.getRecentVerifiedAt().plus(config.getRecentAuthTtl()).isAfter(now()))) throw new ClientException(REAUTH_REQUIRED);
    }
    private LoginSessionVO currentSession(Long user,HttpServletRequest request) {
        String authorization=request.getHeader("Authorization");
        if (authorization==null || !authorization.startsWith("Bearer ")) throw new ClientException(VERSE_SESSION_CHANGED);
        String token=authorization.substring(7);
        try {
            var claims=jwt.parseToken(token);
            if (!user.toString().equals(claims.getSubject()) || claims.getId()==null
                    || !user.toString().equals(redis.opsForValue().get(RedisKeyConstant.USER_LOGIN_TOKEN_KEY+DigestUtil.md5Hex(token)))) throw new ClientException(VERSE_SESSION_CHANGED);
            String device=DeviceUtil.generateDeviceId(request.getHeader("User-Agent"),DeviceUtil.getClientIp(request));
            Object value=redis.opsForHash().get(RedisKeyConstant.USER_DEVICES_KEY+user,device);
            var session=value==null?null:JSON.parseObject(value.toString(),LoginSessionVO.class);
            if (session==null || !token.equals(session.getToken()) || !claims.getId().equals(session.getSessionId())) throw new ClientException(VERSE_SESSION_CHANGED);
            return session;
        } catch (ClientException e) { throw e; } catch (RuntimeException e) { throw new ClientException(VERSE_SESSION_CHANGED); }
    }
    private void checkVersion(ExternalAuthFlowDO row,ExternalIdentityDO identity) {
        if (identity==null || !identity.getBindingVersion().equals(row.getIdentityBindingVersion())) throw new ClientException(FLOW_STATE_CHANGED);
    }
    private void require(ExternalAuthFlowDO row,String purpose,String stage) {
        if (!purpose.equals(row.getPurpose()) || !stage.equals(row.getStage())) throw new ClientException(FLOW_INVALID);
    }
    private void finish(ExternalAuthFlowDO row) { row.setStage("COMPLETED"); row.setCompletedAt(now()); flows.updateById(row); }
    private void cacheResult(ExternalAuthFlowDO row,LoginCompletion result) {
        redis.opsForValue().set(PREFIX+"result:"+row.getFlowId(),aes.encrypt(JSON.toJSONString(result)),remaining(row));
    }
    private LoginCompletion restore(ExternalAuthFlowDO row) {
        if (!"LOGIN".equals(row.getPurpose())) throw new ClientException(FLOW_INVALID);
        String value=redis.opsForValue().get(PREFIX+"result:"+row.getFlowId());
        if (value!=null) {
            var result=JSON.parseObject(aes.decrypt(value),LoginCompletion.class);
            if (result.login()!=null && result.login().getUserId().toString().equals(redis.opsForValue()
                    .get(RedisKeyConstant.USER_LOGIN_TOKEN_KEY+DigestUtil.md5Hex(result.login().getToken())))) return result;
        }
        if (Boolean.TRUE.equals(row.getRegistrationCompleted())) return new LoginCompletion("REGISTERED_LOGIN_FAILED",true,null,"REAUTHENTICATE");
        throw new ClientException(SESSION_REAUTH_REQUIRED);
    }
    private void cleanupSession(com.yonagi.verse.dto.resp.UserLoginRespDTO login) {
        redis.delete(RedisKeyConstant.USER_LOGIN_TOKEN_KEY+DigestUtil.md5Hex(login.getToken()));
        // 比较会话令牌后删除，不能误删同设备随后签发的新会话。
        String script="local v=redis.call('HGETALL',KEYS[1]); for i=1,#v,2 do local s=cjson.decode(v[i+1]); if s.token==ARGV[1] then redis.call('HDEL',KEYS[1],v[i]) end end return 1";
        redis.execute(new DefaultRedisScript<>(script,Long.class),List.of(RedisKeyConstant.USER_DEVICES_KEY+login.getUserId()),login.getToken());
    }
    private List<UserExternalBindingDO> relations(Long identity) {
        return bindings.selectList(Wrappers.lambdaQuery(UserExternalBindingDO.class).eq(UserExternalBindingDO::getExternalIdentityId,identity).orderByAsc(UserExternalBindingDO::getId));
    }
    private String snapshot(List<UserExternalBindingDO> relations) {
        Map<String,Long> result=new LinkedHashMap<>(); relations.forEach(r->result.put(r.getUserId().toString(),r.getId())); return JSON.toJSONString(result);
    }
    private Map<String,Long> candidates(ExternalAuthFlowDO row) {
        Map<String,Long> result=new LinkedHashMap<>(); var json=JSON.parseObject(row.getCandidateSnapshot());
        if (json!=null) json.forEach((k,v)->result.put(k,Long.valueOf(v.toString()))); return result;
    }
    private UserDO user(Long user) { return users.selectOne(Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getUserId,user)); }
    private void active(UserDO user) { if (user==null || !Integer.valueOf(1).equals(user.getStatus())) throw new ClientException(ACTION_NOT_ALLOWED); }
    private Account account(UserDO user) { return user==null || Integer.valueOf(2).equals(user.getStatus()) ? null
            : new Account(user.getUserId().toString(),user.getUsername(),user.getNickname(),null,Integer.valueOf(1).equals(user.getStatus())?"NORMAL":"DISABLED"); }
    private Summary summary(ExternalIdentityDO identity) { return identity==null?null:new Summary(identity.getDisplayName(),identity.getExternalUsername(),
            identity.getEmailEncrypted()==null?null:SensitiveUtil.maskEmail(aes.decrypt(identity.getEmailEncrypted()))); }
    private String decrypt(String value) { return value==null?null:aes.decrypt(value); }
    private Duration remaining(ExternalAuthFlowDO row) { return Duration.between(now(),row.getExpiresAt()); }
    private void cookie(HttpServletResponse response,String id,String value,Duration ttl) {
        response.addHeader("Set-Cookie",ResponseCookie.from("verse_ext_"+id,value).httpOnly(true).secure(config.getFrontendOrigin().startsWith("https://"))
                .sameSite("Lax").path("/api/v1").maxAge(ttl).build().toString());
    }
    private String cookieValue(HttpServletRequest request,String id) {
        if (request.getCookies()==null) return null;
        return Arrays.stream(request.getCookies()).filter(c->c.getName().equals("verse_ext_"+id)).map(Cookie::getValue).findFirst().orElse(null);
    }
    static boolean matches(String expected,String provided) {
        return expected!=null && provided!=null && provided.length()<=128 && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),hash(provided).getBytes(StandardCharsets.US_ASCII));
    }
    static String hash(String value) { return HexFormat.of().formatHex(ExternalProviderAdapter.sha256(value)); }
    static String secret() { byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String id() { return UUID.randomUUID().toString().replace("-",""); }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private static String iso(LocalDateTime value) { return value.atOffset(ZoneOffset.UTC).toString(); }
    private void rate(String key,int limit,Duration window) {
        String script="local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end return n";
        Long count=redis.execute(new DefaultRedisScript<>(script,Long.class),List.of(PREFIX+"rate:"+key),Long.toString(window.toMillis()));
        if (count==null || count>limit) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"操作过于频繁，请稍后重试");
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
    public void cleanup() { flows.cleanup(); }
}
