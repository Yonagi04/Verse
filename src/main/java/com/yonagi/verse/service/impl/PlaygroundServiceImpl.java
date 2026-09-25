package com.yonagi.verse.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.entity.LlmServiceCapabilityDO;
import com.yonagi.verse.dao.entity.PlaygroundSessionDO;
import com.yonagi.verse.dao.entity.PlaygroundTurnDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.LlmServiceCapabilityMapper;
import com.yonagi.verse.dao.mapper.PlaygroundSessionMapper;
import com.yonagi.verse.dao.mapper.PlaygroundTurnMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.dto.resp.PlaygroundDtos;
import com.yonagi.verse.resilience.impl.PlaygroundRateLimiter;
import com.yonagi.verse.service.LlmForwardService;
import com.yonagi.verse.service.PlaygroundService;
import com.yonagi.verse.service.forward.ModelResolver;
import com.yonagi.verse.service.forward.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** PlayGround 私有会话编排；查询和修改始终带租户与创建者条件。 */
@Service
@RequiredArgsConstructor
public class PlaygroundServiceImpl implements PlaygroundService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final TenantMapper tenantMapper;
    private final UserTenantMapper membershipMapper;
    private final LlmServiceMapper serviceMapper;
    private final LlmServiceCapabilityMapper capabilityMapper;
    private final PlaygroundSessionMapper sessionMapper;
    private final PlaygroundTurnMapper turnMapper;
    private final PlaygroundTurnFinalizer finalizer;
    private final ModelResolver modelResolver;
    private final PlaygroundRateLimiter playgroundRateLimiter;
    private final LlmForwardService llmForwardService;

    @Override
    public PlaygroundDtos.Status status(UserContext actor, Long tenantId) {
        TenantDO tenant = requireTenant(actor, tenantId);
        return new PlaygroundDtos.Status(Integer.valueOf(1).equals(tenant.getPlaygroundEnabled()),
                PlaygroundRateLimiter.RPM, PlaygroundRateLimiter.RPH);
    }

    @Override
    public PlaygroundDtos.Models models(UserContext actor, Long tenantId) {
        requireEnabled(actor, tenantId);
        List<PlaygroundDtos.Model> items = serviceMapper.selectList(Wrappers.lambdaQuery(LlmServiceDO.class)
                        .eq(LlmServiceDO::getTenantId, tenantId)
                        .eq(LlmServiceDO::getStatus, 1)
                        .eq(LlmServiceDO::getDelFlag, 0)
                        .orderByAsc(LlmServiceDO::getName)).stream()
                .filter(this::chatAvailable)
                .map(s -> new PlaygroundDtos.Model(id(s.getServiceId()), s.getName(), s.getProvider(),
                        s.getDescription(), s.getContextWindow()))
                .toList();
        return new PlaygroundDtos.Models(items);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlaygroundDtos.Summary create(UserContext actor, Long tenantId, Long serviceId) {
        requireEnabled(actor, tenantId);
        LlmServiceDO service = requireChatService(tenantId, serviceId);
        PlaygroundSessionDO session = new PlaygroundSessionDO();
        session.setSessionId(SnowflakeIdUtil.nextId());
        session.setTenantId(tenantId);
        session.setOwnerUserId(actor.getUserId());
        session.setServiceId(serviceId);
        session.setModelName(service.getName());
        session.setTitle("新会话");
        session.setTurnCount(0);
        session.setGenerating(0);
        session.setDelFlag(0);
        session.setCreateTime(LocalDateTime.now(ZONE));
        session.setUpdateTime(session.getCreateTime());
        sessionMapper.insert(session);
        return summary(session);
    }

    @Override
    public PlaygroundDtos.Sessions sessions(UserContext actor, Long tenantId, int pageNum,
                                            int pageSize, String keyword) {
        requireEnabled(actor, tenantId);
        if (pageNum < 1 || pageSize < 1 || pageSize > 50) {
            throw new ClientException(PlaygroundErrorCodeEnum.INVALID_PAGE);
        }
        var query = Wrappers.lambdaQuery(PlaygroundSessionDO.class)
                .eq(PlaygroundSessionDO::getTenantId, tenantId)
                .eq(PlaygroundSessionDO::getOwnerUserId, actor.getUserId())
                .eq(PlaygroundSessionDO::getDelFlag, 0);
        if (keyword != null && !keyword.isBlank()) query.like(PlaygroundSessionDO::getTitle, keyword.trim());
        query.orderByDesc(PlaygroundSessionDO::getUpdateTime, PlaygroundSessionDO::getSessionId);
        Page<PlaygroundSessionDO> page = sessionMapper.selectPage(new Page<>(pageNum, pageSize), query);
        return new PlaygroundDtos.Sessions(page.getRecords().stream().map(this::summary).toList(),
                page.getTotal(), page.getPages(), pageNum, pageSize);
    }

    @Override
    public PlaygroundDtos.Detail detail(UserContext actor, Long tenantId, Long sessionId) {
        requireEnabled(actor, tenantId);
        PlaygroundSessionDO session = requireSession(actor, tenantId, sessionId);
        List<PlaygroundDtos.Turn> turns = turns(actor, tenantId, sessionId).stream()
                .map(t -> new PlaygroundDtos.Turn(id(t.getTurnId()), t.getPrompt(), t.getReply(),
                        t.getStatus(), t.getRequestId(), iso(t.getCreateTime()), iso(t.getFinishedAt())))
                .toList();
        return new PlaygroundDtos.Detail(id(sessionId), session.getTitle(), id(session.getServiceId()),
                session.getModelName(), chatAvailable(loadService(tenantId, session.getServiceId())),
                session.getTurnCount(), iso(session.getCreateTime()), iso(session.getUpdateTime()), turns);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlaygroundDtos.Summary updateModel(UserContext actor, Long tenantId, Long sessionId, Long serviceId) {
        requireEnabled(actor, tenantId);
        requireSession(actor, tenantId, sessionId);
        LlmServiceDO service = requireChatService(tenantId, serviceId);
        int updated = sessionMapper.update(Wrappers.lambdaUpdate(PlaygroundSessionDO.class)
                .eq(PlaygroundSessionDO::getTenantId, tenantId)
                .eq(PlaygroundSessionDO::getOwnerUserId, actor.getUserId())
                .eq(PlaygroundSessionDO::getSessionId, sessionId)
                .eq(PlaygroundSessionDO::getDelFlag, 0)
                .eq(PlaygroundSessionDO::getTurnCount, 0)
                .eq(PlaygroundSessionDO::getGenerating, 0)
                .set(PlaygroundSessionDO::getServiceId, serviceId)
                .set(PlaygroundSessionDO::getModelName, service.getName())
                .set(PlaygroundSessionDO::getUpdateTime, LocalDateTime.now(ZONE)));
        if (updated != 1) throw new ClientException(PlaygroundErrorCodeEnum.MODEL_LOCKED);
        return summary(requireSession(actor, tenantId, sessionId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(UserContext actor, Long tenantId, Long sessionId) {
        requireEnabled(actor, tenantId);
        requireSession(actor, tenantId, sessionId);
        int updated = sessionMapper.update(Wrappers.lambdaUpdate(PlaygroundSessionDO.class)
                .eq(PlaygroundSessionDO::getTenantId, tenantId)
                .eq(PlaygroundSessionDO::getOwnerUserId, actor.getUserId())
                .eq(PlaygroundSessionDO::getSessionId, sessionId)
                .eq(PlaygroundSessionDO::getDelFlag, 0)
                .eq(PlaygroundSessionDO::getGenerating, 0)
                .set(PlaygroundSessionDO::getDelFlag, 1));
        if (updated != 1) throw new ClientException(PlaygroundErrorCodeEnum.GENERATING);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PreparedTurn prepareSend(UserContext actor, Long tenantId, Long sessionId, String prompt,
                                    String idempotencyKey) {
        requireEnabled(actor, tenantId);
        if (prompt == null || prompt.isBlank() || prompt.length() > 100_000) {
            throw new ClientException(PlaygroundErrorCodeEnum.INVALID_PROMPT);
        }
        String cleanPrompt = prompt.trim();
        try {
            UUID.fromString(idempotencyKey);
        } catch (RuntimeException ex) {
            throw new ClientException(PlaygroundErrorCodeEnum.INVALID_PROMPT);
        }
        PlaygroundTurnDO existing = turnMapper.selectOne(Wrappers.lambdaQuery(PlaygroundTurnDO.class)
                .eq(PlaygroundTurnDO::getTenantId, tenantId)
                .eq(PlaygroundTurnDO::getOwnerUserId, actor.getUserId())
                .eq(PlaygroundTurnDO::getIdempotencyKey, idempotencyKey));
        if (existing != null) throw new DuplicateSendException(existing);
        PlaygroundSessionDO session = requireSession(actor, tenantId, sessionId);
        LlmServiceDO service = requireChatService(tenantId, session.getServiceId());
        List<ChatMessage> messages = new ArrayList<>();
        for (PlaygroundTurnDO previous : turns(actor, tenantId, sessionId)) {
            // 仅完整问答进入后续上下文；停止、失败轮次仍在详情中可读。
            if ("COMPLETED".equals(previous.getStatus())) {
                messages.add(new ChatMessage("user", previous.getPrompt()));
                messages.add(new ChatMessage("assistant", previous.getReply()));
            }
        }
        messages.add(new ChatMessage("user", cleanPrompt));
        if (sessionMapper.claim(tenantId, actor.getUserId(), sessionId) != 1) {
            // 并发同键发送可能在首次查询后才提交，失败时再查一次以返回原轮次。
            PlaygroundTurnDO acceptedTurn = turnMapper.selectOne(Wrappers.lambdaQuery(PlaygroundTurnDO.class)
                    .eq(PlaygroundTurnDO::getTenantId, tenantId)
                    .eq(PlaygroundTurnDO::getOwnerUserId, actor.getUserId())
                    .eq(PlaygroundTurnDO::getIdempotencyKey, idempotencyKey));
            if (acceptedTurn != null) throw new DuplicateSendException(acceptedTurn);
            throw new ClientException(PlaygroundErrorCodeEnum.GENERATING);
        }
        playgroundRateLimiter.check(tenantId, service.getServiceId());
        String requestId = Long.toString(SnowflakeIdUtil.nextId());
        Flux<ServerSentEvent<String>> upstream;
        try {
            upstream = llmForwardService.playgroundChatStream(actor, service.getServiceId(), messages,
                    requestId, java.time.Instant.now());
        } catch (ClientException ex) {
            if ("A000802".equals(ex.getErrorCode())) {
                throw new ClientException(PlaygroundErrorCodeEnum.SHARED_LIMIT);
            }
            throw ex;
        }
        PlaygroundTurnDO turn = new PlaygroundTurnDO();
        turn.setTurnId(SnowflakeIdUtil.nextId());
        turn.setTenantId(tenantId);
        turn.setOwnerUserId(actor.getUserId());
        turn.setSessionId(sessionId);
        turn.setTurnNo(session.getTurnCount() + 1);
        turn.setIdempotencyKey(idempotencyKey);
        turn.setRequestId(requestId);
        turn.setPrompt(cleanPrompt);
        turn.setStatus("PENDING");
        turn.setCreateTime(LocalDateTime.now(ZONE));
        turn.setUpdateTime(turn.getCreateTime());
        turnMapper.insert(turn);
        if (session.getTurnCount() == 0) {
            String title = cleanPrompt.length() > 40 ? cleanPrompt.substring(0, 40) : cleanPrompt;
            sessionMapper.update(Wrappers.lambdaUpdate(PlaygroundSessionDO.class)
                    .eq(PlaygroundSessionDO::getTenantId, tenantId)
                    .eq(PlaygroundSessionDO::getOwnerUserId, actor.getUserId())
                    .eq(PlaygroundSessionDO::getSessionId, sessionId)
                    .set(PlaygroundSessionDO::getTitle, title));
        }
        return new PreparedTurn(requestId, events(actor, turn, upstream));
    }

    private Flux<ServerSentEvent<String>> events(UserContext actor, PlaygroundTurnDO turn,
                                                   Flux<ServerSentEvent<String>> upstream) {
        StringBuilder reply = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicInteger sequence = new AtomicInteger();
        long[] lastSaved = {System.currentTimeMillis()};
        String turnId = id(turn.getTurnId());
        ServerSentEvent<String> accepted = event("accepted", JSONObject.of(
                "sessionId", id(turn.getSessionId()), "turnId", turnId,
                "requestId", turn.getRequestId(), "status", "PENDING"));
        Flux<ServerSentEvent<String>> deltas = upstream.handle((chunk, sink) -> {
            String data = chunk.data();
            if (data == null || "[DONE]".equals(data.trim())) return;
            JSONObject envelope = JSON.parseObject(data);
            if (envelope == null) return;
            if (envelope.containsKey("error")) {
                sink.error(new ClientException(PlaygroundErrorCodeEnum.UPSTREAM_ERROR));
                return;
            }
            JSONArray choices = envelope.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) return;
            JSONObject choice = choices.getJSONObject(0);
            if (choice != null && ("tool_calls".equals(choice.getString("finish_reason"))
                    || "function_call".equals(choice.getString("finish_reason")))) {
                sink.error(new ClientException(PlaygroundErrorCodeEnum.UNSUPPORTED_RESPONSE));
                return;
            }
            JSONObject delta = choice == null ? null : choice.getJSONObject("delta");
            if (delta == null) return;
            if (delta.containsKey("tool_calls") || delta.containsKey("function_call")
                    || (delta.containsKey("content") && !(delta.get("content") instanceof String))) {
                sink.error(new ClientException(PlaygroundErrorCodeEnum.UNSUPPORTED_RESPONSE));
                return;
            }
            String text = delta.getString("content");
            if (text == null || text.isEmpty()) return;
            synchronized (reply) {
                reply.append(text);
                if (System.currentTimeMillis() - lastSaved[0] >= 250) {
                    turnMapper.savePartial(turn.getTenantId(), actor.getUserId(), turn.getSessionId(),
                            turn.getTurnId(), reply.toString());
                    lastSaved[0] = System.currentTimeMillis();
                }
            }
            sink.next(event("delta", JSONObject.of("turnId", turnId,
                    "seq", sequence.incrementAndGet(), "text", text)));
        });
        Flux<ServerSentEvent<String>> completed = deltas.concatWith(Mono.fromSupplier(() -> {
            String full = reply.toString();
            finish(actor, turn, full, "COMPLETED", finished);
            return event("completed", JSONObject.of("turnId", turnId, "status", "COMPLETED", "reply", full));
        })).onErrorResume(error -> {
            finish(actor, turn, reply.toString(), "FAILED", finished);
            PlaygroundErrorCodeEnum code = error instanceof ClientException client
                    && PlaygroundErrorCodeEnum.UNSUPPORTED_RESPONSE.code().equals(client.getErrorCode())
                    ? PlaygroundErrorCodeEnum.UNSUPPORTED_RESPONSE : PlaygroundErrorCodeEnum.UPSTREAM_ERROR;
            return Mono.just(event("error", JSONObject.of("turnId", turnId, "status", "FAILED",
                    "code", code.code(), "message", code.message())));
        });
        return Flux.just(accepted).concatWith(completed)
                .doOnCancel(() -> finish(actor, turn, reply.toString(), "STOPPED", finished));
    }

    private void finish(UserContext actor, PlaygroundTurnDO turn, String reply, String status,
                        AtomicBoolean finished) {
        if (!finished.compareAndSet(false, true)) return;
        try {
            finalizer.finish(turn.getTenantId(), actor.getUserId(), turn.getSessionId(),
                    turn.getTurnId(), reply, status);
        } catch (RuntimeException ex) {
            finished.set(false);
            throw ex;
        }
    }

    private ServerSentEvent<String> event(String name, JSONObject data) {
        return ServerSentEvent.<String>builder().event(name).data(data.toJSONString()).build();
    }

    /** 重复发送返回原轮次，控制器据此响应 HTTP 409。 */
    public static final class DuplicateSendException extends ClientException {
        private final PlaygroundTurnDO original;
        public DuplicateSendException(PlaygroundTurnDO original) {
            super(PlaygroundErrorCodeEnum.DUPLICATE_SEND);
            this.original = original;
        }
        public PlaygroundTurnDO original() { return original; }
    }

    private TenantDO requireTenant(UserContext actor, Long tenantId) {
        if (actor == null || actor.getApiKeyId() != null || actor.getUserId() == null
                || tenantId == null || !tenantId.equals(actor.getCurrentTenantId())) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_CONTEXT_MISMATCH);
        }
        UserTenantDO membership = membershipMapper.selectOne(Wrappers.lambdaQuery(UserTenantDO.class)
                .eq(UserTenantDO::getTenantId, tenantId)
                .eq(UserTenantDO::getUserId, actor.getUserId())
                .isNull(UserTenantDO::getLeftAt));
        if (membership == null) throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        TenantDO tenant = tenantMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId)
                .eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0));
        if (tenant == null) throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        return tenant;
    }

    private void requireEnabled(UserContext actor, Long tenantId) {
        if (!Integer.valueOf(1).equals(requireTenant(actor, tenantId).getPlaygroundEnabled())) {
            throw new ClientException(PlaygroundErrorCodeEnum.DISABLED);
        }
    }

    private LlmServiceDO loadService(Long tenantId, Long serviceId) {
        if (serviceId == null) return null;
        return serviceMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getServiceId, serviceId)
                .eq(LlmServiceDO::getStatus, 1)
                .eq(LlmServiceDO::getDelFlag, 0));
    }

    private boolean chatAvailable(LlmServiceDO service) {
        if (service == null) return false;
        LlmServiceCapabilityDO binding = capabilityMapper.selectOne(
                Wrappers.lambdaQuery(LlmServiceCapabilityDO.class)
                        .eq(LlmServiceCapabilityDO::getServiceId, service.getServiceId())
                        .eq(LlmServiceCapabilityDO::getOperation, ModelOperation.CHAT_COMPLETIONS.name())
                        .eq(LlmServiceCapabilityDO::getEnabled, 1));
        if (binding == null) return false;
        try {
            modelResolver.requireBinding(service, ModelOperation.CHAT_COMPLETIONS);
            return true;
        } catch (ClientException ex) {
            return false;
        }
    }

    private LlmServiceDO requireChatService(Long tenantId, Long serviceId) {
        LlmServiceDO service = loadService(tenantId, serviceId);
        if (!chatAvailable(service)) throw new ClientException(PlaygroundErrorCodeEnum.MODEL_UNAVAILABLE);
        return service;
    }

    private PlaygroundSessionDO requireSession(UserContext actor, Long tenantId, Long sessionId) {
        PlaygroundSessionDO session = sessionId == null ? null : sessionMapper.selectOne(
                Wrappers.lambdaQuery(PlaygroundSessionDO.class)
                        .eq(PlaygroundSessionDO::getTenantId, tenantId)
                        .eq(PlaygroundSessionDO::getOwnerUserId, actor.getUserId())
                        .eq(PlaygroundSessionDO::getSessionId, sessionId)
                        .eq(PlaygroundSessionDO::getDelFlag, 0));
        if (session == null) throw new ClientException(PlaygroundErrorCodeEnum.SESSION_NOT_FOUND);
        return session;
    }

    private List<PlaygroundTurnDO> turns(UserContext actor, Long tenantId, Long sessionId) {
        return turnMapper.selectList(Wrappers.lambdaQuery(PlaygroundTurnDO.class)
                .eq(PlaygroundTurnDO::getTenantId, tenantId)
                .eq(PlaygroundTurnDO::getOwnerUserId, actor.getUserId())
                .eq(PlaygroundTurnDO::getSessionId, sessionId)
                .orderByAsc(PlaygroundTurnDO::getTurnNo));
    }

    private PlaygroundDtos.Summary summary(PlaygroundSessionDO s) {
        return new PlaygroundDtos.Summary(id(s.getSessionId()), s.getTitle(), id(s.getServiceId()),
                s.getModelName(), s.getTurnCount(), iso(s.getCreateTime()), iso(s.getUpdateTime()));
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.atZone(ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private String id(Long value) { return value == null ? null : value.toString(); }
}
