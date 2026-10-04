package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.cache.QueryCached;
import com.yonagi.verse.common.cache.QueryCatalogue.Access;
import com.yonagi.verse.service.cache.QueryCacheBehaviors;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.resilience.impl.PlaygroundRateLimiter;
import com.yonagi.verse.service.LlmForwardService;
import com.yonagi.verse.service.PlaygroundService;
import com.yonagi.verse.service.PlaygroundWorkbenchService;
import com.yonagi.verse.service.forward.ChatMessage;
import com.yonagi.verse.service.forward.ModelResolver;
import com.yonagi.verse.service.pricing.PricingResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.yonagi.verse.common.cache.QueryCacheTtl.*;
import static com.yonagi.verse.common.constant.RedisKeyConstant.*;

/** 二期编排：数据库保护组锁，调用尝试独立，所有上下文来自服务端。 */
@Service
@RequiredArgsConstructor
public class PlaygroundWorkbenchServiceImpl implements PlaygroundWorkbenchService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> ACTIVE = Set.of("PENDING", "STREAMING", "STOPPING");
    private final PlaygroundService playgroundService;
    private final PlaygroundWorkspaceMapper workspaceMapper;
    private final PlaygroundAttemptMapper attemptMapper;
    private final LlmServiceMapper modelMapper;
    private final TokenUsageMapper usageMapper;
    private final ModelResolver modelResolver;
    private final PricingResolver pricingResolver;
    private final PlaygroundRateLimiter rateLimiter;
    private final LlmForwardService forwardService;
    private final PlaygroundAttemptFinalizer finalizer;
    private final Map<Long, Sinks.One<Boolean>> stops = new ConcurrentHashMap<>();

    private void enabled(UserContext actor, Long tenant) {
        if (!playgroundService.status(actor, tenant).enabled()) throw new ClientException(PlaygroundErrorCodeEnum.DISABLED);
    }

    @Override
    @QueryCached(keyPrefix = PLAYGROUND_WORKBENCH_MODELS_KEY, seconds = HOURS_4, access = Access.PLAYGROUND,
            tables = {"t_tenant", "t_user_tenant", "t_llm_service", "t_llm_service_capability"}, behavior = QueryCacheBehaviors.WorkbenchModels.class)
    public List<JSONObject> models(UserContext actor, Long tenant) {
        return withCurrentPrices(tenant, modelMetadata(actor, tenant));
    }

    /** 缓存只保存模型元数据，不冻结随请求时间变化的最终价格。 */
    public List<JSONObject> modelMetadata(UserContext actor, Long tenant) {
        return playgroundService.models(actor, tenant).items().stream().map(m -> {
            LlmServiceDO service = model(tenant, Long.valueOf(m.serviceId()));
            return json("serviceId", m.serviceId(), "name", m.name(), "provider", m.provider(),
                    "description", m.description(), "contextWindow", m.contextWindow(),
                    "capabilities", caps(service));
        }).toList();
    }

    /** 命中元数据后仍按当前请求时间计算价格，兼容跨高峰及价格生效边界。 */
    public List<JSONObject> withCurrentPrices(Long tenant, List<JSONObject> metadata) {
        Instant startedAt = Instant.now();
        return metadata.stream().map(item -> {
            JSONObject result = new JSONObject(item);
            var price = pricingResolver.resolve(tenant, Long.valueOf(item.getString("serviceId")), startedAt);
            result.put("pricing", price.priced() ? json("currency", price.currency(),
                    "billingMode", price.billingMode(), "inputPriceFen", price.cacheMissInputPriceFen(),
                    "outputPriceFen", price.outputPriceFen(), "requestPriceFen", price.requestPriceFen(),
                    "periodType", price.periodType()) : null);
            return result;
        }).toList();
    }

    @Override
    @QueryCached(keyPrefix = PLAYGROUND_PRESET_LIST_KEY, seconds = HOURS_4, access = Access.PLAYGROUND,
            tables = {"t_tenant", "t_user_tenant", "t_playground_workspace"}, behavior = QueryCacheBehaviors.WorkbenchList.class)
    public List<JSONObject> list(UserContext actor, Long tenant, String kind, String keyword) {
        enabled(actor, tenant);
        if (!Set.of("GROUP", "PRESET").contains(kind)) throw PlaygroundConfiguration.invalid();
        var q = Wrappers.lambdaQuery(PlaygroundWorkspaceDO.class).eq(PlaygroundWorkspaceDO::getTenantId, tenant)
                .eq(PlaygroundWorkspaceDO::getOwnerUserId, actor.getUserId()).eq(PlaygroundWorkspaceDO::getKind, kind)
                .eq(PlaygroundWorkspaceDO::getDelFlag, 0);
        if (keyword != null && !keyword.isBlank()) q.and(search -> search.like(PlaygroundWorkspaceDO::getTitle, keyword.trim())
                .or().like(PlaygroundWorkspaceDO::getDescription, keyword.trim()));
        return workspaceMapper.selectList(q.orderByDesc(PlaygroundWorkspaceDO::getUpdateTime)
                .last("LIMIT 200")).stream().map(this::workspaceView).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JSONObject create(UserContext actor, Long tenant, String kind, JSONObject body) {
        enabled(actor, tenant);
        PlaygroundConfiguration.fields(body, "title", "description", "config");
        if (!Set.of("GROUP", "PRESET").contains(kind)) throw PlaygroundConfiguration.invalid();
        JSONObject config = PlaygroundConfiguration.normalize(configuration(body));
        PlaygroundWorkspaceDO w = new PlaygroundWorkspaceDO();
        w.setWorkspaceId(SnowflakeIdUtil.nextId()); w.setTenantId(tenant); w.setOwnerUserId(actor.getUserId());
        w.setKind(kind); w.setTitle(title(body.getString("title"), kind.equals("GROUP") ? "新会话" : null));
        w.setDescription(description(body.getString("description"))); w.setGenerating(0); w.setRevision(1); w.setDelFlag(0);
        JSONObject payload = config;
        if (kind.equals("PRESET")) payload = json("versions", JSONArray.of(version(config, 1)));
        else payload.put("prefix", new JSONArray());
        w.setPayload(payload.toJSONString()); w.setCreateTime(now()); w.setUpdateTime(w.getCreateTime());
        workspaceMapper.insert(w);
        return workspaceView(w);
    }

    @Override
    @QueryCached(keyPrefix = PLAYGROUND_PRESET_INFO_KEY, seconds = HOURS_4, access = Access.PLAYGROUND,
            tables = {"t_tenant", "t_user_tenant", "t_playground_workspace"}, behavior = QueryCacheBehaviors.WorkbenchDetail.class)
    public JSONObject detail(UserContext actor, Long tenant, Long id) {
        enabled(actor, tenant);
        PlaygroundWorkspaceDO w = owned(actor, tenant, id, false);
        JSONObject result = workspaceView(w);
        if (w.getKind().equals("GROUP")) {
            result.put("attempts", attempts(actor, tenant, id).stream().map(this::attemptView).toList());
            JSONObject source = JSON.parseObject(w.getPayload()).getJSONObject("source");
            if (source != null) {
                result.put("sourceAvailable", workspaceMapper.selectCount(Wrappers.lambdaQuery(PlaygroundWorkspaceDO.class)
                        .eq(PlaygroundWorkspaceDO::getWorkspaceId, source.getString("groupId"))
                        .eq(PlaygroundWorkspaceDO::getTenantId, tenant).eq(PlaygroundWorkspaceDO::getOwnerUserId, actor.getUserId())
                        .eq(PlaygroundWorkspaceDO::getDelFlag, 0)) > 0);
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JSONObject update(UserContext actor, Long tenant, Long id, JSONObject body) {
        enabled(actor, tenant);
        PlaygroundConfiguration.fields(body, "title", "description", "config", "revision");
        PlaygroundWorkspaceDO w = owned(actor, tenant, id, true);
        idle(w);
        if (body.getInteger("revision") == null || !w.getRevision().equals(body.getInteger("revision"))) throw new ClientException(PlaygroundErrorCodeEnum.RESOURCE_CONFLICT);
        if (body.containsKey("title")) w.setTitle(title(body.getString("title"), null));
        if (body.containsKey("description")) w.setDescription(description(body.getString("description")));
        JSONObject payload = JSON.parseObject(w.getPayload());
        if (body.containsKey("config")) {
            JSONObject config = PlaygroundConfiguration.normalize(configuration(body));
            if (w.getKind().equals("PRESET")) payload.getJSONArray("versions").add(version(config, w.getRevision() + 1));
            else {
                if (!attempts(actor, tenant, id).isEmpty() && !topology(payload).equals(topology(config))) throw new ClientException(PlaygroundErrorCodeEnum.MODEL_LOCKED);
                config.put("prefix", payload.getJSONArray("prefix"));
                if (payload.containsKey("source")) config.put("source", payload.get("source"));
                payload = config;
            }
        }
        w.setPayload(payload.toJSONString()); w.setRevision(w.getRevision() + 1); w.setUpdateTime(now()); workspaceMapper.updateById(w);
        return workspaceView(w);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(UserContext actor, Long tenant, Long id) {
        enabled(actor, tenant);
        PlaygroundWorkspaceDO w = owned(actor, tenant, id, true); idle(w);
        w.setDelFlag(1); w.setUpdateTime(now()); workspaceMapper.updateById(w); return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JSONObject round(UserContext actor, Long tenant, Long id, String prompt, String key) {
        enabled(actor, tenant); final String normalizedKey = uuid(key);
        if (prompt == null || prompt.isBlank() || prompt.length() > 100_000) throw new ClientException(PlaygroundErrorCodeEnum.INVALID_PROMPT);
        PlaygroundWorkspaceDO w = owned(actor, tenant, id, true); group(w);
        List<PlaygroundAttemptDO> history = attempts(actor, tenant, id);
        List<PlaygroundAttemptDO> existing = history.stream().filter(a -> a.getRoundId().equals(normalizedKey)).toList();
        if (!existing.isEmpty()) return json("roundId", normalizedKey, "attempts", existing.stream().map(this::attemptView).toList());
        idle(w);
        JSONObject payload = JSON.parseObject(w.getPayload());
        // 参数冲突属于整个配置错误，必须在接受调用前阻止；模型失效仅影响该栏。
        for (Object item : payload.getJSONArray("lanes")) {
            JSONObject lane = (JSONObject) item;
            LlmServiceDO service = availableModel(tenant, Long.valueOf(lane.getString("serviceId")));
            if (service != null) PlaygroundConfiguration.parameters(lane.getJSONObject("config"), caps(service));
        }
        int number = history.stream().mapToInt(PlaygroundAttemptDO::getRoundNo).max().orElse(0) + 1;
        w.setGenerating(1); w.setUpdateTime(now());
        if (history.isEmpty() && "新会话".equals(w.getTitle())) w.setTitle(prompt.trim().substring(0, Math.min(40, prompt.trim().length())));
        workspaceMapper.updateById(w);
        List<JSONObject> accepted = new ArrayList<>();
        for (Object item : payload.getJSONArray("lanes")) {
            JSONObject lane = (JSONObject) item;
            PlaygroundAttemptDO a = attempt(w, normalizedKey, number, lane.getString("laneId"), 1, prompt.trim(), Long.valueOf(lane.getString("serviceId")));
            JSONObject config = lane.getJSONObject("config");
            JSONArray messages = context(payload, history, lane.getString("laneId"), Integer.MAX_VALUE, false);
            if (config.getString("system") != null && !config.getString("system").isBlank()) messages.add(0, json("role", "system", "content", config.getString("system")));
            messages.add(json("role", "user", "content", a.getPrompt()));
            // 即使预检失败也保留原输入和配置，模型恢复后重试仍使用同一份上下文。
            LlmServiceDO configured = modelMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                    .eq(LlmServiceDO::getTenantId, tenant).eq(LlmServiceDO::getServiceId, a.getServiceId()).eq(LlmServiceDO::getDelFlag, 0));
            JSONObject request = json("model", configured == null ? "不可用模型" : configured.getName(), "messages", messages, "stream", true);
            if (config.get("temperature") != null) request.put("temperature", config.get("temperature"));
            if (config.get("topP") != null) request.put("top_p", config.get("topP"));
            if (config.get("maxTokens") != null) request.put("max_tokens", config.get("maxTokens"));
            a.setSnapshot(json("request", request, "config", config, "modelName", request.getString("model"),
                    "provider", configured == null ? null : configured.getProvider()).toJSONString());
            try {
                LlmServiceDO service = model(tenant, a.getServiceId());
                request.put("model", service.getName());
                request.putAll(PlaygroundConfiguration.parameters(config, caps(service)));
                checkContext(service, request);
                a.setSnapshot(json("request", request, "config", config, "modelName", service.getName(),
                        "provider", service.getProvider(), "capabilities", caps(service), "presetId", payload.get("presetId"),
                        "presetVersion", payload.get("presetVersion")).toJSONString());
            } catch (ClientException ex) {
                a.setStatus("FAILED"); a.setErrorJson(error(ex).toJSONString()); a.setFinishedAt(now());
            }
            attemptMapper.insert(a); accepted.add(attemptView(a));
        }
        workspaceMapper.release(id);
        return json("roundId", normalizedKey, "attempts", accepted);
    }

    @Override
    public Flux<ServerSentEvent<String>> stream(UserContext actor, Long tenant, Long attemptId) {
        enabled(actor, tenant);
        PlaygroundAttemptDO a = ownedAttempt(actor, tenant, attemptId);
        owned(actor, tenant, a.getWorkspaceId(), false);
        // 调用级 CAS：任何重复订阅都不能重复调用供应商。
        int claimed = attemptMapper.update(Wrappers.lambdaUpdate(PlaygroundAttemptDO.class)
                .eq(PlaygroundAttemptDO::getAttemptId, attemptId).eq(PlaygroundAttemptDO::getTenantId, tenant)
                .eq(PlaygroundAttemptDO::getOwnerUserId, actor.getUserId()).eq(PlaygroundAttemptDO::getStatus, "PENDING")
                .set(PlaygroundAttemptDO::getStatus, "STREAMING").set(PlaygroundAttemptDO::getUpdateTime, now()));
        if (claimed != 1) throw new ClientException(PlaygroundErrorCodeEnum.DUPLICATE_SEND);
        Sinks.One<Boolean> stopSignal = Sinks.one(); stops.put(attemptId, stopSignal);
        AtomicBoolean subscribed = new AtomicBoolean();
        return Flux.defer(() -> {
            if (!subscribed.compareAndSet(false, true)) return Flux.error(new ClientException(PlaygroundErrorCodeEnum.DUPLICATE_SEND));
            long started = System.currentTimeMillis();
            StringBuffer reply = new StringBuffer(); AtomicLong first = new AtomicLong(-1); AtomicLong saved = new AtomicLong(started);
            AtomicBoolean done = new AtomicBoolean(); AtomicBoolean stopped = new AtomicBoolean();
            JSONObject snapshot = JSON.parseObject(a.getSnapshot()); JSONObject request = snapshot.getJSONObject("request");
            Runnable cancel = () -> finish(a, reply.toString(), "STOPPED", first.get(), started, null, done);
            Flux<ServerSentEvent<String>> upstream = Flux.defer(() -> {
                String currentStatus = ownedAttempt(actor, tenant, attemptId).getStatus();
                if (Set.of("STOPPED", "STOPPING").contains(currentStatus)) { stopped.set(true); return Flux.empty(); }
                LlmServiceDO service = model(tenant, a.getServiceId());
                PlaygroundConfiguration.parameters(snapshot.getJSONObject("config"), caps(service));
                checkContext(service, request); rateLimiter.check(tenant, a.getServiceId());
                List<ChatMessage> messages = request.getJSONArray("messages").stream().map(m -> {
                    JSONObject value = (JSONObject) m; return new ChatMessage(value.getString("role"), value.getString("content"));
                }).toList();
                JSONObject params = new JSONObject();
                for (String name : Set.of("temperature", "top_p", "max_tokens")) if (request.containsKey(name)) params.put(name, request.get(name));
                return forwardService.playgroundChatStream(actor, a.getServiceId(), messages, params, a.getRequestId(), Instant.ofEpochMilli(started));
            });
            // 跨实例停止也能生效；数据库查询放在有界阻塞线程池。
            Mono<Boolean> remoteStop = Flux.interval(Duration.ofMillis(500)).publishOn(Schedulers.boundedElastic())
                    .map(t -> ownedAttempt(actor, tenant, attemptId).getStatus()).filter(s -> Set.of("STOPPING", "STOPPED").contains(s)).next().map(s -> true);
            Mono<Boolean> until = Mono.firstWithSignal(stopSignal.asMono(), remoteStop).doOnNext(s -> stopped.set(true));
            Flux<ServerSentEvent<String>> chunks = upstream.takeUntilOther(until).handle((chunk, sink) -> {
                if (chunk.data() == null || "[DONE]".equals(chunk.data().trim())) return;
                JSONObject data = JSON.parseObject(chunk.data());
                if (data == null) return;
                if (data.containsKey("error")) { sink.error(new ClientException(PlaygroundErrorCodeEnum.UPSTREAM_ERROR)); return; }
                JSONArray choices = data.getJSONArray("choices"); if (choices == null || choices.isEmpty()) return;
                JSONObject choice = choices.getJSONObject(0); JSONObject delta = choice.getJSONObject("delta");
                if ("tool_calls".equals(choice.getString("finish_reason")) || "function_call".equals(choice.getString("finish_reason"))
                        || delta != null && (delta.containsKey("tool_calls") || delta.containsKey("function_call")
                        || delta.get("content") != null && !(delta.get("content") instanceof String))) {
                    sink.error(new ClientException(PlaygroundErrorCodeEnum.UNSUPPORTED_RESPONSE)); return;
                }
                String text = delta == null ? null : delta.getString("content"); if (text == null || text.isEmpty()) return;
                first.compareAndSet(-1, System.currentTimeMillis() - started); reply.append(text);
                if (System.currentTimeMillis() - saved.get() >= 250) {
                    attemptMapper.update(Wrappers.lambdaUpdate(PlaygroundAttemptDO.class).eq(PlaygroundAttemptDO::getAttemptId, attemptId)
                            .eq(PlaygroundAttemptDO::getStatus, "STREAMING").set(PlaygroundAttemptDO::getReply, reply.toString())
                            .set(PlaygroundAttemptDO::getUpdateTime, now())); saved.set(System.currentTimeMillis());
                }
                sink.next(event("delta", json("attemptId", attemptId.toString(), "text", text)));
            });
            return Flux.just(event("accepted", json("attemptId", attemptId.toString(), "requestId", a.getRequestId())))
                    .concatWith(chunks).concatWith(Mono.fromSupplier(() -> {
                        finish(a, reply.toString(), stopped.get() ? "STOPPED" : "COMPLETED", first.get(), started, null, done);
                        return event("completed", attemptView(ownedAttempt(actor, tenant, attemptId)));
                    })).onErrorResume(ex -> {
                        JSONObject safeError = error(ex);
                        finish(a, reply.toString(), "FAILED", first.get(), started, safeError.toJSONString(), done);
                        return Mono.just(event("error", attemptView(ownedAttempt(actor, tenant, attemptId))));
                    }).doOnCancel(cancel).doFinally(signal -> stops.remove(attemptId, stopSignal));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public boolean stop(UserContext actor, Long tenant, Long id) {
        enabled(actor, tenant); PlaygroundAttemptDO a = ownedAttempt(actor, tenant, id);
        if ("PENDING".equals(a.getStatus()) && finalizer.stopPending(a)) return true;
        if (ACTIVE.contains(a.getStatus())) {
            attemptMapper.update(Wrappers.lambdaUpdate(PlaygroundAttemptDO.class).eq(PlaygroundAttemptDO::getAttemptId, id)
                    .eq(PlaygroundAttemptDO::getStatus, "STREAMING").set(PlaygroundAttemptDO::getStatus, "STOPPING")
                    .set(PlaygroundAttemptDO::getUpdateTime, now()));
            Sinks.One<Boolean> signal = stops.get(id); if (signal != null) signal.tryEmitValue(true);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JSONObject retry(UserContext actor, Long tenant, Long id, String key) {
        enabled(actor, tenant); final String normalizedKey = uuid(key); PlaygroundAttemptDO original = ownedAttempt(actor, tenant, id);
        PlaygroundWorkspaceDO w = owned(actor, tenant, original.getWorkspaceId(), true);
        List<PlaygroundAttemptDO> history = attempts(actor, tenant, w.getWorkspaceId());
        for (PlaygroundAttemptDO prior : history) {
            JSONObject data = JSON.parseObject(prior.getSnapshot());
            if (normalizedKey.equals(data.getString("retryKey"))) {
                if (!id.toString().equals(data.getString("retrySource"))) throw PlaygroundConfiguration.invalid();
                return attemptView(prior);
            }
        }
        idle(w);
        if (!Set.of("FAILED", "STOPPED").contains(original.getStatus())) throw PlaygroundConfiguration.invalid();
        JSONObject snapshot = JSON.parseObject(original.getSnapshot());
        if (snapshot.getJSONObject("request") == null) throw new ClientException(PlaygroundErrorCodeEnum.MODEL_UNAVAILABLE);
        int number = history.stream().filter(a -> a.getRoundId().equals(original.getRoundId()) && a.getLaneId().equals(original.getLaneId()))
                .mapToInt(PlaygroundAttemptDO::getAttemptNo).max().orElse(0) + 1;
        snapshot.put("retryKey", normalizedKey); snapshot.put("retrySource", id.toString());
        PlaygroundAttemptDO a = attempt(w, original.getRoundId(), original.getRoundNo(), original.getLaneId(), number, original.getPrompt(), original.getServiceId());
        a.setSnapshot(snapshot.toJSONString()); w.setGenerating(1); w.setUpdateTime(now()); workspaceMapper.updateById(w); attemptMapper.insert(a);
        return attemptView(a);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JSONObject fork(UserContext actor, Long tenant, Long id, JSONObject body) {
        enabled(actor, tenant);
        PlaygroundConfiguration.fields(body, "attemptId", "laneId", "before", "config", "title");
        PlaygroundWorkspaceDO source = owned(actor, tenant, id, true); group(source); idle(source);
        List<PlaygroundAttemptDO> history = attempts(actor, tenant, id);
        JSONObject payload = JSON.parseObject(source.getPayload());
        String lane = body.getString("laneId");
        int cutoff = Integer.MAX_VALUE; boolean before = Boolean.TRUE.equals(body.getBoolean("before"));
        JSONObject config = configuration(body);
        PlaygroundAttemptDO target = null;
        if (body.getString("attemptId") != null) {
            Long targetId;
            try { targetId = Long.valueOf(body.getString("attemptId")); } catch (RuntimeException ex) { throw PlaygroundConfiguration.invalid(); }
            target = ownedAttempt(actor, tenant, targetId);
            if (!id.equals(target.getWorkspaceId())) throw PlaygroundConfiguration.invalid();
            if (!before && !"COMPLETED".equals(target.getStatus())) throw PlaygroundConfiguration.invalid();
            lane = target.getLaneId(); cutoff = target.getRoundNo();
        }
        final String selected = lane;
        if (payload.getJSONArray("lanes").stream().noneMatch(l -> selected != null && selected.equals(((JSONObject) l).getString("laneId")))) throw PlaygroundConfiguration.invalid();
        if (config == null) {
            if (target == null) config = editable(payload);
            else config = json("synced", true, "lanes", JSONArray.of(json("laneId", UUID.randomUUID().toString(),
                    "serviceId", target.getServiceId().toString(), "config", JSON.parseObject(target.getSnapshot()).getJSONObject("config"))));
        }
        JSONObject created = create(actor, tenant, "GROUP", json("title", title(body.getString("title"), source.getTitle() + " · 分叉"), "config", config));
        PlaygroundWorkspaceDO branch = owned(actor, tenant, Long.valueOf(created.getString("id")), false);
        JSONObject next = JSON.parseObject(branch.getPayload());
        JSONArray prefix = context(payload, history, lane, cutoff, before);
        // 从用户所选的旧完成尝试分叉时，分叉点必须采用该尝试，而非后来重试的回答。
        if (target != null && !before && prefix.size() >= 2) {
            prefix.set(prefix.size() - 2, json("role", "user", "content", target.getPrompt()));
            prefix.set(prefix.size() - 1, json("role", "assistant", "content", target.getReply()));
        }
        next.put("prefix", prefix);
        int sourceIndex = 0;
        for (int i = 0; i < payload.getJSONArray("lanes").size(); i++) if (lane.equals(payload.getJSONArray("lanes").getJSONObject(i).getString("laneId"))) sourceIndex = i;
        next.put("source", json("groupId", id.toString(), "title", source.getTitle(), "roundNo", cutoff == Integer.MAX_VALUE ? null : cutoff,
                "laneLabel", "配置 " + (char) ('A' + sourceIndex)));
        branch.setPayload(next.toJSONString()); workspaceMapper.updateById(branch); return workspaceView(branch);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JSONObject restore(UserContext actor, Long tenant, Long id, int version) {
        enabled(actor, tenant); PlaygroundWorkspaceDO preset = owned(actor, tenant, id, true);
        if (!"PRESET".equals(preset.getKind())) throw PlaygroundConfiguration.invalid();
        JSONArray versions = JSON.parseObject(preset.getPayload()).getJSONArray("versions");
        JSONObject found = versions.stream().map(v -> (JSONObject) v).filter(v -> v.getIntValue("number") == version).findFirst().orElseThrow(PlaygroundConfiguration::invalid);
        return update(actor, tenant, id, json("revision", preset.getRevision(), "config", found.getJSONObject("config")));
    }

    /** 只取每轮该栏最新完成尝试；停止/失败输出永不进入上下文。 */
    static JSONArray context(JSONObject payload, List<PlaygroundAttemptDO> history, String lane, int cutoff, boolean before) {
        JSONArray result = payload.getJSONArray("prefix") == null ? new JSONArray() : JSON.parseArray(payload.getJSONArray("prefix").toJSONString());
        Map<Integer, PlaygroundAttemptDO> completed = new TreeMap<>();
        for (PlaygroundAttemptDO a : history) {
            if (!lane.equals(a.getLaneId()) || !"COMPLETED".equals(a.getStatus()) || a.getRoundNo() > cutoff || before && a.getRoundNo() == cutoff) continue;
            PlaygroundAttemptDO prior = completed.get(a.getRoundNo());
            if (prior == null || prior.getAttemptNo() < a.getAttemptNo()) completed.put(a.getRoundNo(), a);
        }
        for (PlaygroundAttemptDO a : completed.values()) {
            result.add(json("role", "user", "content", a.getPrompt())); result.add(json("role", "assistant", "content", a.getReply()));
        }
        return result;
    }

    private void finish(PlaygroundAttemptDO a, String reply, String status, long first, long started, String error, AtomicBoolean done) {
        if (done.compareAndSet(false, true)) {
            try { finalizer.finish(a, reply, status, first < 0 ? null : first, System.currentTimeMillis() - started, error); }
            catch (RuntimeException ex) { done.set(false); throw ex; }
        }
    }
    private JSONObject attemptView(PlaygroundAttemptDO a) {
        TokenUsageDO usage = usageMapper.selectOne(Wrappers.lambdaQuery(TokenUsageDO.class).eq(TokenUsageDO::getTenantId, a.getTenantId())
                .eq(TokenUsageDO::getUserId, a.getOwnerUserId()).eq(TokenUsageDO::getSource, "PLAYGROUND")
                .eq(TokenUsageDO::getRequestId, a.getRequestId()).last("LIMIT 1"));
        boolean preflight = a.getDurationMs() == null;
        JSONObject failure = a.getErrorJson() == null ? null : JSON.parseObject(a.getErrorJson());
        if (failure != null && failure.getString("code") != null && failure.getString("code").startsWith("A")) preflight = true;
        boolean recent = a.getFinishedAt() == null || a.getFinishedAt().isAfter(now().minusMinutes(2));
        String settlement = usage == null ? (!preflight && recent ? "PENDING" : "UNKNOWN") : usage.getCostStatus();
        JSONObject metrics = json("inputTokens", usage == null ? null : usage.getInputTokens(), "outputTokens", usage == null ? null : usage.getOutputTokens(),
                "evidence", usage == null ? "UNKNOWN" : usage.getUsageSource(), "costStatus", settlement,
                "costFen", usage == null ? null : usage.getEstimatedCostFen(), "currency", usage == null ? null : usage.getCurrency(),
                "firstContentMs", a.getFirstContentMs(), "durationMs", a.getDurationMs());
        return json("attemptId", a.getAttemptId().toString(), "groupId", a.getWorkspaceId().toString(), "roundId", a.getRoundId(),
                "roundNo", a.getRoundNo(), "laneId", a.getLaneId(), "attemptNo", a.getAttemptNo(), "requestId", a.getRequestId(),
                "serviceId", a.getServiceId().toString(), "prompt", a.getPrompt(), "reply", a.getReply(), "status", a.getStatus(),
                "snapshot", JSON.parseObject(a.getSnapshot()), "error", a.getErrorJson() == null ? null : JSON.parseObject(a.getErrorJson()),
                "metrics", metrics, "createdAt", iso(a.getCreateTime()), "finishedAt", iso(a.getFinishedAt()));
    }
    private JSONObject workspaceView(PlaygroundWorkspaceDO w) {
        return json("id", w.getWorkspaceId().toString(), "title", w.getTitle(), "description", w.getDescription(), "kind", w.getKind(),
                "payload", JSON.parseObject(w.getPayload()), "revision", w.getRevision(), "generating", w.getGenerating() == 1,
                "createdAt", iso(w.getCreateTime()), "updatedAt", iso(w.getUpdateTime()));
    }
    private PlaygroundWorkspaceDO owned(UserContext actor, Long tenant, Long id, boolean lock) {
        PlaygroundWorkspaceDO w = lock ? workspaceMapper.lock(tenant, actor.getUserId(), id) : workspaceMapper.selectOne(Wrappers.lambdaQuery(PlaygroundWorkspaceDO.class)
                .eq(PlaygroundWorkspaceDO::getTenantId, tenant).eq(PlaygroundWorkspaceDO::getOwnerUserId, actor.getUserId())
                .eq(PlaygroundWorkspaceDO::getWorkspaceId, id).eq(PlaygroundWorkspaceDO::getDelFlag, 0));
        if (w == null) throw new ClientException(PlaygroundErrorCodeEnum.SESSION_NOT_FOUND); return w;
    }
    private PlaygroundAttemptDO ownedAttempt(UserContext actor, Long tenant, Long id) {
        PlaygroundAttemptDO a = attemptMapper.selectOne(Wrappers.lambdaQuery(PlaygroundAttemptDO.class).eq(PlaygroundAttemptDO::getTenantId, tenant)
                .eq(PlaygroundAttemptDO::getOwnerUserId, actor.getUserId()).eq(PlaygroundAttemptDO::getAttemptId, id));
        if (a == null) throw new ClientException(PlaygroundErrorCodeEnum.SESSION_NOT_FOUND); return a;
    }
    private List<PlaygroundAttemptDO> attempts(UserContext actor, Long tenant, Long id) {
        return attemptMapper.selectList(Wrappers.lambdaQuery(PlaygroundAttemptDO.class).eq(PlaygroundAttemptDO::getTenantId, tenant)
                .eq(PlaygroundAttemptDO::getOwnerUserId, actor.getUserId()).eq(PlaygroundAttemptDO::getWorkspaceId, id)
                .orderByAsc(PlaygroundAttemptDO::getRoundNo, PlaygroundAttemptDO::getAttemptNo, PlaygroundAttemptDO::getId));
    }
    private LlmServiceDO availableModel(Long tenant, Long id) {
        LlmServiceDO model = modelMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class).eq(LlmServiceDO::getTenantId, tenant)
                .eq(LlmServiceDO::getServiceId, id).eq(LlmServiceDO::getDelFlag, 0).eq(LlmServiceDO::getStatus, 1));
        if (model == null) return null;
        try { modelResolver.requireBinding(model, ModelOperation.CHAT_COMPLETIONS); return model; }
        catch (ClientException ex) { return null; }
    }
    private LlmServiceDO model(Long tenant, Long id) {
        LlmServiceDO model = availableModel(tenant, id); if (model == null) throw new ClientException(PlaygroundErrorCodeEnum.MODEL_UNAVAILABLE); return model;
    }
    private JSONObject caps(LlmServiceDO model) { return PlaygroundConfiguration.capabilities(model, modelResolver.protocolFor(model, ModelOperation.CHAT_COMPLETIONS)); }
    private void checkContext(LlmServiceDO model, JSONObject request) {
        // 文本长度保守预检，最终 Token 上限由实际模型验证；不截断用户历史。
        long chars = request.getJSONArray("messages").stream().mapToLong(m -> ((JSONObject) m).getString("content").length()).sum();
        long output = request.getLongValue("max_tokens");
        if (model.getContextWindow() != null && chars + output > model.getContextWindow()) throw new ClientException(PlaygroundErrorCodeEnum.CONTEXT_LIMIT);
    }
    private PlaygroundAttemptDO attempt(PlaygroundWorkspaceDO w, String round, int roundNo, String lane, int number, String prompt, Long model) {
        PlaygroundAttemptDO a = new PlaygroundAttemptDO(); a.setAttemptId(SnowflakeIdUtil.nextId()); a.setRequestId(Long.toString(SnowflakeIdUtil.nextId()));
        a.setTenantId(w.getTenantId()); a.setOwnerUserId(w.getOwnerUserId()); a.setWorkspaceId(w.getWorkspaceId()); a.setRoundId(round);
        a.setRoundNo(roundNo); a.setLaneId(lane); a.setAttemptNo(number); a.setServiceId(model); a.setPrompt(prompt); a.setReply("");
        a.setStatus("PENDING"); a.setCreateTime(now()); a.setUpdateTime(a.getCreateTime()); return a;
    }
    private JSONObject error(Throwable ex) {
        JSONObject result = json("code", PlaygroundErrorCodeEnum.UPSTREAM_ERROR.code(), "message", PlaygroundErrorCodeEnum.UPSTREAM_ERROR.message());
        if (ex instanceof AbstractException e) {
            result.put("code", e.getErrorCode()); result.put("message", e.getErrorMessage());
            if ("A000802".equals(e.getErrorCode())) { result.put("code", PlaygroundErrorCodeEnum.SHARED_LIMIT.code()); result.put("message", PlaygroundErrorCodeEnum.SHARED_LIMIT.message()); }
        }
        if (ex instanceof PlaygroundRateLimiter.LimitException limit) { result.put("reason", limit.getReason()); result.put("retryAfterSeconds", limit.getRetryAfterSeconds()); }
        return result;
    }
    private static JSONObject json(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private JSONObject configuration(JSONObject body) {
        Object value = body.get("config");
        if (value == null) return null;
        if (!(value instanceof Map<?, ?>)) throw PlaygroundConfiguration.invalid();
        return JSON.parseObject(JSON.toJSONString(value));
    }
    private static JSONObject editable(JSONObject payload) {
        JSONObject config = JSON.parseObject(payload.toJSONString()); config.remove("prefix"); config.remove("source"); return config;
    }
    private List<String> topology(JSONObject config) { return config.getJSONArray("lanes").stream().map(l -> ((JSONObject) l).getString("laneId") + ":" + ((JSONObject) l).getString("serviceId")).toList(); }
    private JSONObject version(JSONObject config, int number) { return json("number", number, "createdAt", iso(now()), "config", config); }
    private String title(String title, String fallback) {
        String value = title == null || title.isBlank() ? fallback : title.trim();
        if (value == null) throw PlaygroundConfiguration.invalid(); return value.substring(0, Math.min(60, value.length()));
    }
    private String description(String description) { if (description != null && description.length() > 500) throw PlaygroundConfiguration.invalid(); return description; }
    private void idle(PlaygroundWorkspaceDO w) { if (w.getGenerating() == 1) throw new ClientException(PlaygroundErrorCodeEnum.GENERATING); }
    private void group(PlaygroundWorkspaceDO w) { if (!"GROUP".equals(w.getKind())) throw PlaygroundConfiguration.invalid(); }
    private String uuid(String key) {
        try { if (key == null || key.length() != 36) throw PlaygroundConfiguration.invalid(); return UUID.fromString(key).toString(); }
        catch (RuntimeException ex) { throw PlaygroundConfiguration.invalid(); }
    }
    private LocalDateTime now() { return LocalDateTime.now(ZONE); }
    private String iso(LocalDateTime time) { return time == null ? null : time.atZone(ZONE).toOffsetDateTime().toString(); }
    private ServerSentEvent<String> event(String name, JSONObject value) { return ServerSentEvent.<String>builder().event(name).data(value.toJSONString()).build(); }
}
