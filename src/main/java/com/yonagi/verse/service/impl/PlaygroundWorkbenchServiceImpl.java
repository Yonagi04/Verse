package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.yonagi.verse.common.cache.QueryCached;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.util.JsonNumbers;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.entity.PlaygroundAttemptDO;
import com.yonagi.verse.dao.entity.PlaygroundWorkspaceDO;
import com.yonagi.verse.dao.entity.TokenUsageDO;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.PlaygroundAttemptMapper;
import com.yonagi.verse.dao.mapper.PlaygroundWorkspaceMapper;
import com.yonagi.verse.dao.mapper.TokenUsageMapper;
import com.yonagi.verse.dto.playground.WorkbenchTypes.AcceptedVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.AttemptVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.CapabilitiesVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.ConfigDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.DeltaVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.ErrorVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.LaneDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.MessageDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.MetricsVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.ModelVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.PayloadDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.PricingVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.RequestDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.RoundVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.RunConfigDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.SnapshotDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.SourceDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.VersionDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.WorkspaceVO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.CreateReqDTO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.ForkReqDTO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.UpdateReqDTO;
import com.yonagi.verse.resilience.impl.PlaygroundRateLimiter;
import com.yonagi.verse.service.LlmForwardService;
import com.yonagi.verse.service.PlaygroundService;
import com.yonagi.verse.service.PlaygroundWorkbenchService;
import com.yonagi.verse.service.budget.CostLimitExceededException;
import com.yonagi.verse.service.forward.ChatMessage;
import com.yonagi.verse.service.forward.ModelResolver;
import com.yonagi.verse.service.playground.PlaygroundAccessPolicy;
import com.yonagi.verse.service.playground.PlaygroundConfiguration;
import com.yonagi.verse.service.playground.PlaygroundCostError;
import com.yonagi.verse.service.playground.PlaygroundQueryAccess;
import com.yonagi.verse.service.playground.WorkbenchDetailCacheBehavior;
import com.yonagi.verse.service.playground.WorkbenchJson;
import com.yonagi.verse.service.playground.WorkbenchListCacheBehavior;
import com.yonagi.verse.service.playground.WorkbenchModelsCacheBehavior;
import com.yonagi.verse.service.pricing.PricingResolver;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.yonagi.verse.common.cache.QueryCacheTtl.HOURS_4;
import static com.yonagi.verse.common.constant.RedisKeyConstant.PLAYGROUND_PRESET_INFO_KEY;
import static com.yonagi.verse.common.constant.RedisKeyConstant.PLAYGROUND_PRESET_LIST_KEY;
import static com.yonagi.verse.common.constant.RedisKeyConstant.PLAYGROUND_WORKBENCH_MODELS_KEY;

/** 二期编排：数据库保护组锁，调用尝试独立，所有上下文来自服务端。 */
@Service
@RequiredArgsConstructor
public class PlaygroundWorkbenchServiceImpl implements PlaygroundWorkbenchService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String GROUP = "GROUP";
    private static final String PRESET = "PRESET";
    private static final String PENDING = "PENDING";
    private static final String STREAMING = "STREAMING";
    private static final String STOPPING = "STOPPING";
    private static final String COMPLETED = "COMPLETED";
    private static final String STOPPED = "STOPPED";
    private static final String FAILED = "FAILED";
    private static final Set<String> ACTIVE = Set.of(PENDING, STREAMING, STOPPING);
    private static final Set<String> STOP_STATES = Set.of(STOPPING, STOPPED);
    private static final Duration REMOTE_STOP_INTERVAL = Duration.ofMillis(500);
    private static final long REPLY_SAVE_INTERVAL_MS = 250L;
    private static final String DEFAULT_GROUP_TITLE = "新会话";

    private final PlaygroundService playgroundService;
    private final PlaygroundAccessPolicy playgroundAccess;
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
        playgroundAccess.requireEnabled(actor, tenant);
    }

    @Override
    @QueryCached(keyPrefix = PLAYGROUND_WORKBENCH_MODELS_KEY, seconds = HOURS_4, access = PlaygroundQueryAccess.class,
            tables = {"t_tenant", "t_user_tenant", "t_llm_service", "t_llm_service_capability"},
            behavior = WorkbenchModelsCacheBehavior.class)
    public List<ModelVO> models(UserContext actor, Long tenant) {
        return withCurrentPrices(tenant, modelMetadata(actor, tenant));
    }

    /** 缓存只保存模型元数据，不冻结随请求时间变化的最终价格。 */
    public List<ModelVO> modelMetadata(UserContext actor, Long tenant) {
        return playgroundService.models(actor, tenant).items().stream().map(item -> {
            LlmServiceDO service = model(tenant, Long.valueOf(item.serviceId()));
            return new ModelVO(item.serviceId(), item.name(), item.provider(), item.description(),
                    item.contextWindow(), caps(service), null);
        }).toList();
    }

    /** 命中元数据后按当前请求时间计算价格，不修改缓存中的模型视图。 */
    public List<ModelVO> withCurrentPrices(Long tenant, List<ModelVO> metadata) {
        Instant startedAt = Instant.now();
        return metadata.stream().map(item -> {
            PricingSnapshot price = pricingResolver.resolve(tenant, Long.valueOf(item.getServiceId()), startedAt);
            PricingVO pricing = price.priced() ? new PricingVO(price.currency(), price.billingMode(),
                    price.cacheMissInputPriceFen(), price.outputPriceFen(), price.requestPriceFen(),
                    price.periodType()) : null;
            return new ModelVO(item.getServiceId(), item.getName(), item.getProvider(), item.getDescription(),
                    item.getContextWindow(), item.getCapabilities(), pricing);
        }).toList();
    }

    @Override
    @QueryCached(keyPrefix = PLAYGROUND_PRESET_LIST_KEY, seconds = HOURS_4, access = PlaygroundQueryAccess.class,
            tables = {"t_tenant", "t_user_tenant", "t_playground_workspace"},
            behavior = WorkbenchListCacheBehavior.class)
    public List<WorkspaceVO> list(UserContext actor, Long tenant, String kind, String keyword) {
        enabled(actor, tenant);
        requireKind(kind);
        var query = Wrappers.lambdaQuery(PlaygroundWorkspaceDO.class).eq(PlaygroundWorkspaceDO::getTenantId, tenant)
                .eq(PlaygroundWorkspaceDO::getOwnerUserId, actor.getUserId()).eq(PlaygroundWorkspaceDO::getKind, kind)
                .eq(PlaygroundWorkspaceDO::getDelFlag, 0);
        if (keyword != null && !keyword.isBlank()) {
            query.and(search -> search.like(PlaygroundWorkspaceDO::getTitle, keyword.trim())
                    .or().like(PlaygroundWorkspaceDO::getDescription, keyword.trim()));
        }
        return workspaceMapper.selectList(query.orderByDesc(PlaygroundWorkspaceDO::getUpdateTime)
                .last("LIMIT 200")).stream().map(this::workspaceView).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceVO create(UserContext actor, Long tenant, String kind, CreateReqDTO body) {
        enabled(actor, tenant);
        requireKind(kind);
        if (body == null) {
            throw PlaygroundConfiguration.invalid();
        }
        ConfigDTO config = PlaygroundConfiguration.normalize(body.getConfig());
        PlaygroundWorkspaceDO workspace = new PlaygroundWorkspaceDO();
        workspace.setWorkspaceId(SnowflakeIdUtil.nextId());
        workspace.setTenantId(tenant);
        workspace.setOwnerUserId(actor.getUserId());
        workspace.setKind(kind);
        workspace.setTitle(title(body.getTitle(), GROUP.equals(kind) ? DEFAULT_GROUP_TITLE : null));
        workspace.setDescription(description(body.getDescription()));
        workspace.setGenerating(0);
        workspace.setRevision(1);
        workspace.setDelFlag(0);
        PayloadDTO payload = PRESET.equals(kind)
                ? PayloadDTO.builder().versions(List.of(version(config, 1))).build()
                : groupPayload(config, List.of(), null);
        workspace.setPayload(WorkbenchJson.write(payload));
        workspace.setCreateTime(now());
        workspace.setUpdateTime(workspace.getCreateTime());
        workspaceMapper.insert(workspace);
        return workspaceView(workspace);
    }

    @Override
    @QueryCached(keyPrefix = PLAYGROUND_PRESET_INFO_KEY, seconds = HOURS_4, access = PlaygroundQueryAccess.class,
            tables = {"t_tenant", "t_user_tenant", "t_playground_workspace"},
            behavior = WorkbenchDetailCacheBehavior.class)
    public WorkspaceVO detail(UserContext actor, Long tenant, Long id) {
        enabled(actor, tenant);
        PlaygroundWorkspaceDO workspace = owned(actor, tenant, id, false);
        WorkspaceVO result = workspaceView(workspace);
        if (GROUP.equals(workspace.getKind())) {
            result.setAttempts(attempts(actor, tenant, id).stream().map(this::attemptView).toList());
            SourceDTO source = result.getPayload().getSource();
            if (source != null) {
                result.setSourceAvailable(workspaceMapper.selectCount(Wrappers.lambdaQuery(PlaygroundWorkspaceDO.class)
                        .eq(PlaygroundWorkspaceDO::getWorkspaceId, source.getGroupId())
                        .eq(PlaygroundWorkspaceDO::getTenantId, tenant)
                        .eq(PlaygroundWorkspaceDO::getOwnerUserId, actor.getUserId())
                        .eq(PlaygroundWorkspaceDO::getDelFlag, 0)) > 0);
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceVO update(UserContext actor, Long tenant, Long id, UpdateReqDTO body) {
        enabled(actor, tenant);
        if (body == null || body.getSuppliedFields() == null) {
            throw PlaygroundConfiguration.invalid();
        }
        Set<String> supplied = body.getSuppliedFields();
        BigDecimal revision = body.getRevision();
        if ((revision != null || supplied.contains("revision"))
                && !JsonNumbers.isPositiveInteger(revision, Integer.MAX_VALUE)) {
            throw PlaygroundConfiguration.invalid();
        }
        PlaygroundWorkspaceDO workspace = owned(actor, tenant, id, true);
        idle(workspace);
        if (revision == null || !workspace.getRevision().equals(revision.intValue())) {
            throw new ClientException(PlaygroundErrorCodeEnum.RESOURCE_CONFLICT);
        }
        if (supplied.contains("title")) {
            workspace.setTitle(title(body.getTitle(), null));
        }
        if (supplied.contains("description")) {
            workspace.setDescription(description(body.getDescription()));
        }
        PayloadDTO payload = payload(workspace);
        if (supplied.contains("config")) {
            ConfigDTO config = PlaygroundConfiguration.normalize(body.getConfig());
            if (PRESET.equals(workspace.getKind())) {
                payload.getVersions().add(version(config, workspace.getRevision() + 1));
            } else {
                if (!attempts(actor, tenant, id).isEmpty()
                        && !topology(payload.getLanes()).equals(topology(config.getLanes()))) {
                    throw new ClientException(PlaygroundErrorCodeEnum.MODEL_LOCKED);
                }
                payload = groupPayload(config, payload.getPrefix(), payload.getSource());
            }
        }
        workspace.setPayload(WorkbenchJson.write(payload));
        workspace.setRevision(workspace.getRevision() + 1);
        workspace.setUpdateTime(now());
        workspaceMapper.updateById(workspace);
        return workspaceView(workspace);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(UserContext actor, Long tenant, Long id) {
        enabled(actor, tenant);
        PlaygroundWorkspaceDO workspace = owned(actor, tenant, id, true);
        idle(workspace);
        workspace.setDelFlag(1);
        workspace.setUpdateTime(now());
        workspaceMapper.updateById(workspace);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoundVO round(UserContext actor, Long tenant, Long id, String prompt, String key) {
        enabled(actor, tenant);
        String normalizedKey = uuid(key);
        if (prompt == null || prompt.isBlank() || prompt.length() > 100_000) {
            throw new ClientException(PlaygroundErrorCodeEnum.INVALID_PROMPT);
        }
        PlaygroundWorkspaceDO workspace = owned(actor, tenant, id, true);
        group(workspace);
        List<PlaygroundAttemptDO> history = attempts(actor, tenant, id);
        List<PlaygroundAttemptDO> existing = history.stream()
                .filter(attempt -> attempt.getRoundId().equals(normalizedKey)).toList();
        if (!existing.isEmpty()) {
            return new RoundVO(normalizedKey, existing.stream().map(this::attemptView).toList());
        }
        idle(workspace);
        PayloadDTO payload = payload(workspace);
        // 参数冲突属于整个配置错误；模型失效只影响该栏。
        for (LaneDTO lane : payload.getLanes()) {
            LlmServiceDO service = availableModel(tenant, Long.valueOf(lane.getServiceId()));
            if (service != null) {
                PlaygroundConfiguration.parameters(lane.getConfig(), caps(service));
            }
        }
        int number = history.stream().mapToInt(PlaygroundAttemptDO::getRoundNo).max().orElse(0) + 1;
        workspace.setGenerating(1);
        workspace.setUpdateTime(now());
        if (history.isEmpty() && DEFAULT_GROUP_TITLE.equals(workspace.getTitle())) {
            workspace.setTitle(prompt.trim().substring(0, Math.min(40, prompt.trim().length())));
        }
        workspaceMapper.updateById(workspace);
        List<AttemptVO> accepted = new ArrayList<>();
        for (LaneDTO lane : payload.getLanes()) {
            PlaygroundAttemptDO attempt = attempt(workspace, normalizedKey, number, lane.getLaneId(), 1,
                    prompt.trim(), Long.valueOf(lane.getServiceId()));
            prepareAttempt(tenant, payload, history, lane, attempt);
            attemptMapper.insert(attempt);
            accepted.add(attemptView(attempt));
        }
        workspaceMapper.release(id);
        return new RoundVO(normalizedKey, accepted);
    }

    private void prepareAttempt(Long tenant, PayloadDTO payload, List<PlaygroundAttemptDO> history,
                                LaneDTO lane, PlaygroundAttemptDO attempt) {
        RunConfigDTO config = lane.getConfig();
        List<MessageDTO> messages = context(payload, history, lane.getLaneId(), Integer.MAX_VALUE, false);
        if (config.getSystem() != null && !config.getSystem().isBlank()) {
            messages.addFirst(new MessageDTO("system", config.getSystem()));
        }
        messages.add(new MessageDTO("user", attempt.getPrompt()));
        // 即使预检失败，也冻结原输入与上下文供后续重试。
        LlmServiceDO configured = modelMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getTenantId, tenant).eq(LlmServiceDO::getServiceId, attempt.getServiceId())
                .eq(LlmServiceDO::getDelFlag, 0));
        String modelName = configured == null ? "不可用模型" : configured.getName();
        RequestDTO request = new RequestDTO(modelName, messages, true,
                config.getTemperature(), config.getTopP(), config.getMaxTokens());
        SnapshotDTO snapshot = SnapshotDTO.builder().request(request).config(config).modelName(modelName)
                .provider(configured == null ? null : configured.getProvider()).build();
        attempt.setSnapshot(WorkbenchJson.write(snapshot));
        try {
            LlmServiceDO service = model(tenant, attempt.getServiceId());
            request.setModel(service.getName());
            CapabilitiesVO capabilities = caps(service);
            Map<String, Object> parameters = PlaygroundConfiguration.parameters(config, capabilities);
            request.setTemperature(decimalParameter(parameters, "temperature"));
            request.setTopP(decimalParameter(parameters, "top_p"));
            request.setMaxTokens(decimalParameter(parameters, "max_tokens"));
            checkContext(service, request);
            attempt.setSnapshot(WorkbenchJson.write(snapshot.toBuilder().modelName(service.getName())
                    .provider(service.getProvider()).capabilities(capabilities)
                    .presetId(payload.getPresetId()).presetVersion(payload.getPresetVersion()).build()));
        } catch (ClientException ex) {
            attempt.setStatus(FAILED);
            attempt.setErrorJson(WorkbenchJson.write(error(ex)));
            attempt.setFinishedAt(now());
        }
    }

    private static BigDecimal decimalParameter(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        return value == null ? null : new BigDecimal(value.toString());
    }

    @Override
    public Flux<ServerSentEvent<String>> stream(UserContext actor, Long tenant, Long attemptId) {
        enabled(actor, tenant);
        PlaygroundAttemptDO attempt = ownedAttempt(actor, tenant, attemptId);
        owned(actor, tenant, attempt.getWorkspaceId(), false);
        // 调用级 CAS：任何重复订阅都不能重复调用供应商。
        int claimed = attemptMapper.update(Wrappers.lambdaUpdate(PlaygroundAttemptDO.class)
                .eq(PlaygroundAttemptDO::getAttemptId, attemptId).eq(PlaygroundAttemptDO::getTenantId, tenant)
                .eq(PlaygroundAttemptDO::getOwnerUserId, actor.getUserId()).eq(PlaygroundAttemptDO::getStatus, PENDING)
                .set(PlaygroundAttemptDO::getStatus, STREAMING).set(PlaygroundAttemptDO::getUpdateTime, now()));
        if (claimed != 1) {
            throw new ClientException(PlaygroundErrorCodeEnum.DUPLICATE_SEND);
        }
        Sinks.One<Boolean> stopSignal = Sinks.one();
        stops.put(attemptId, stopSignal);
        long preparedStarted = System.currentTimeMillis();
        SnapshotDTO preparedSnapshot = snapshot(attempt);
        RequestDTO preparedRequest = preparedSnapshot.getRequest();
        Flux<ServerSentEvent<String>> preparedUpstream;
        try {
            LlmServiceDO service = model(tenant, attempt.getServiceId());
            PlaygroundConfiguration.parameters(preparedSnapshot.getConfig(), caps(service));
            checkContext(service, preparedRequest);
            rateLimiter.check(tenant, attempt.getServiceId());
            List<ChatMessage> messages = preparedRequest.getMessages().stream()
                    .map(message -> new ChatMessage(message.getRole(), message.getContent())).toList();
            preparedUpstream = forwardService.playgroundChatStream(actor, attempt.getServiceId(), messages,
                    forwardParameters(preparedRequest), attempt.getRequestId(), Instant.ofEpochMilli(preparedStarted));
        } catch (RuntimeException rejected) {
            if (rejected instanceof CostLimitExceededException budget) {
                budget.requestId(attempt.getRequestId());
            }
            finish(attempt, "", FAILED, -1, preparedStarted,
                    WorkbenchJson.write(error(rejected)), new AtomicBoolean());
            stops.remove(attemptId, stopSignal);
            throw rejected;
        }
        AtomicBoolean subscribed = new AtomicBoolean();
        return Flux.defer(() -> {
            if (!subscribed.compareAndSet(false, true)) {
                return Flux.error(new ClientException(PlaygroundErrorCodeEnum.DUPLICATE_SEND));
            }
            long started = System.currentTimeMillis();
            StringBuffer reply = new StringBuffer();
            AtomicLong first = new AtomicLong(-1);
            AtomicLong saved = new AtomicLong(started);
            AtomicBoolean done = new AtomicBoolean();
            AtomicBoolean stopped = new AtomicBoolean();
            Runnable cancel = () -> finish(attempt, reply.toString(), STOPPED, first.get(), started, null, done);
            Flux<ServerSentEvent<String>> upstream = Flux.defer(() -> {
                if (STOP_STATES.contains(ownedAttempt(actor, tenant, attemptId).getStatus())) {
                    stopped.set(true);
                    return Flux.empty();
                }
                return preparedUpstream;
            });
            // 跨实例停止仍通过有界阻塞线程池核对数据库状态。
            Mono<Boolean> remoteStop = Flux.interval(REMOTE_STOP_INTERVAL).publishOn(Schedulers.boundedElastic())
                    .map(tick -> ownedAttempt(actor, tenant, attemptId).getStatus())
                    .filter(STOP_STATES::contains).next().map(status -> true);
            Mono<Boolean> until = Mono.firstWithSignal(stopSignal.asMono(), remoteStop)
                    .doOnNext(signal -> stopped.set(true));
            Flux<ServerSentEvent<String>> chunks = upstream.takeUntilOther(until).handle((chunk, sink) -> {
                String content = textDelta(chunk.data());
                if (content == null || content.isEmpty()) {
                    return;
                }
                first.compareAndSet(-1, System.currentTimeMillis() - started);
                reply.append(content);
                if (System.currentTimeMillis() - saved.get() >= REPLY_SAVE_INTERVAL_MS) {
                    attemptMapper.update(Wrappers.lambdaUpdate(PlaygroundAttemptDO.class)
                            .eq(PlaygroundAttemptDO::getAttemptId, attemptId)
                            .eq(PlaygroundAttemptDO::getStatus, STREAMING)
                            .set(PlaygroundAttemptDO::getReply, reply.toString())
                            .set(PlaygroundAttemptDO::getUpdateTime, now()));
                    saved.set(System.currentTimeMillis());
                }
                sink.next(event("delta", new DeltaVO(attemptId.toString(), content)));
            });
            return Flux.just(event("accepted", new AcceptedVO(attemptId.toString(), attempt.getRequestId())))
                    .concatWith(chunks).concatWith(Mono.fromSupplier(() -> {
                        finish(attempt, reply.toString(), stopped.get() ? STOPPED : COMPLETED,
                                first.get(), started, null, done);
                        return event("completed", attemptView(ownedAttempt(actor, tenant, attemptId)));
                    })).onErrorResume(ex -> {
                        finish(attempt, reply.toString(), FAILED, first.get(), started,
                                WorkbenchJson.write(error(ex)), done);
                        return Mono.just(event("error", attemptView(ownedAttempt(actor, tenant, attemptId))));
                    }).doOnCancel(cancel).doFinally(signal -> stops.remove(attemptId, stopSignal));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static Map<String, Object> forwardParameters(RequestDTO request) {
        Map<String, Object> parameters = new HashMap<>();
        if (request.getTemperature() != null) {
            parameters.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            parameters.put("top_p", request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            parameters.put("max_tokens", request.getMaxTokens());
        }
        return parameters;
    }

    private static String textDelta(String raw) {
        if (raw == null || raw.isBlank() || "[DONE]".equals(raw.trim())) {
            return null;
        }
        JsonNode data;
        try {
            data = WorkbenchJson.tree(raw);
        } catch (ServerException ex) {
            throw new ClientException(null, ex, PlaygroundErrorCodeEnum.UPSTREAM_ERROR);
        }
        if (data == null || data.isNull()) {
            return null;
        }
        if (!data.isObject() || data.has("error")) {
            throw new ClientException(PlaygroundErrorCodeEnum.UPSTREAM_ERROR);
        }
        JsonNode choices = data.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");
        String finishReason = choice.path("finish_reason").textValue();
        if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)
                || delta.has("tool_calls") || delta.has("function_call")
                || delta.hasNonNull("content") && !delta.get("content").isTextual()) {
            throw new ClientException(PlaygroundErrorCodeEnum.UNSUPPORTED_RESPONSE);
        }
        return delta.path("content").textValue();
    }

    @Override
    public boolean stop(UserContext actor, Long tenant, Long id) {
        enabled(actor, tenant);
        PlaygroundAttemptDO attempt = ownedAttempt(actor, tenant, id);
        if (PENDING.equals(attempt.getStatus()) && finalizer.stopPending(attempt)) {
            return true;
        }
        if (ACTIVE.contains(attempt.getStatus())) {
            attemptMapper.update(Wrappers.lambdaUpdate(PlaygroundAttemptDO.class)
                    .eq(PlaygroundAttemptDO::getAttemptId, id)
                    .eq(PlaygroundAttemptDO::getStatus, STREAMING).set(PlaygroundAttemptDO::getStatus, STOPPING)
                    .set(PlaygroundAttemptDO::getUpdateTime, now()));
            Sinks.One<Boolean> signal = stops.get(id);
            if (signal != null) {
                signal.tryEmitValue(true);
            }
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AttemptVO retry(UserContext actor, Long tenant, Long id, String key) {
        enabled(actor, tenant);
        String normalizedKey = uuid(key);
        PlaygroundAttemptDO original = ownedAttempt(actor, tenant, id);
        PlaygroundWorkspaceDO workspace = owned(actor, tenant, original.getWorkspaceId(), true);
        List<PlaygroundAttemptDO> history = attempts(actor, tenant, workspace.getWorkspaceId());
        for (PlaygroundAttemptDO prior : history) {
            SnapshotDTO priorSnapshot = snapshot(prior);
            if (normalizedKey.equals(priorSnapshot.getRetryKey())) {
                if (!id.toString().equals(priorSnapshot.getRetrySource())) {
                    throw PlaygroundConfiguration.invalid();
                }
                return attemptView(prior);
            }
        }
        idle(workspace);
        if (!FAILED.equals(original.getStatus()) && !STOPPED.equals(original.getStatus())) {
            throw PlaygroundConfiguration.invalid();
        }
        SnapshotDTO snapshot = snapshot(original);
        if (snapshot.getRequest() == null) {
            throw new ClientException(PlaygroundErrorCodeEnum.MODEL_UNAVAILABLE);
        }
        int number = history.stream().filter(attempt -> attempt.getRoundId().equals(original.getRoundId())
                        && attempt.getLaneId().equals(original.getLaneId()))
                .mapToInt(PlaygroundAttemptDO::getAttemptNo).max().orElse(0) + 1;
        snapshot.setRetryKey(normalizedKey);
        snapshot.setRetrySource(id.toString());
        PlaygroundAttemptDO attempt = attempt(workspace, original.getRoundId(), original.getRoundNo(),
                original.getLaneId(), number, original.getPrompt(), original.getServiceId());
        attempt.setSnapshot(WorkbenchJson.write(snapshot));
        workspace.setGenerating(1);
        workspace.setUpdateTime(now());
        workspaceMapper.updateById(workspace);
        attemptMapper.insert(attempt);
        return attemptView(attempt);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceVO fork(UserContext actor, Long tenant, Long id, ForkReqDTO body) {
        enabled(actor, tenant);
        if (body == null) {
            throw PlaygroundConfiguration.invalid();
        }
        PlaygroundWorkspaceDO source = owned(actor, tenant, id, true);
        group(source);
        idle(source);
        List<PlaygroundAttemptDO> history = attempts(actor, tenant, id);
        PayloadDTO payload = payload(source);
        String lane = body.getLaneId();
        int cutoff = Integer.MAX_VALUE;
        boolean before = Boolean.TRUE.equals(body.getBefore());
        ConfigDTO config = body.getConfig();
        PlaygroundAttemptDO target = null;
        if (body.getAttemptId() != null) {
            Long targetId;
            try {
                targetId = Long.valueOf(body.getAttemptId());
            } catch (NumberFormatException ex) {
                throw PlaygroundConfiguration.invalid();
            }
            target = ownedAttempt(actor, tenant, targetId);
            if (!id.equals(target.getWorkspaceId()) || !before && !COMPLETED.equals(target.getStatus())) {
                throw PlaygroundConfiguration.invalid();
            }
            lane = target.getLaneId();
            cutoff = target.getRoundNo();
        }
        String selected = lane;
        if (selected == null || payload.getLanes().stream().noneMatch(item -> selected.equals(item.getLaneId()))) {
            throw PlaygroundConfiguration.invalid();
        }
        if (config == null) {
            config = target == null ? editable(payload)
                    : new ConfigDTO(List.of(new LaneDTO(UUID.randomUUID().toString(), target.getServiceId().toString(),
                            snapshot(target).getConfig())), true, null, null);
        }
        WorkspaceVO created = create(actor, tenant, GROUP,
                new CreateReqDTO(title(body.getTitle(), source.getTitle() + " · 分叉"), null, config));
        PlaygroundWorkspaceDO branch = owned(actor, tenant, Long.valueOf(created.getId()), false);
        PayloadDTO next = payload(branch);
        List<MessageDTO> prefix = context(payload, history, lane, cutoff, before);
        // 从旧完成尝试分叉时，分叉点采用该尝试，而非后来重试的回答。
        if (target != null && !before && prefix.size() >= 2) {
            prefix.set(prefix.size() - 2, new MessageDTO("user", target.getPrompt()));
            prefix.set(prefix.size() - 1, new MessageDTO("assistant", target.getReply()));
        }
        next.setPrefix(prefix);
        int sourceIndex = 0;
        for (int i = 0; i < payload.getLanes().size(); i++) {
            if (lane.equals(payload.getLanes().get(i).getLaneId())) {
                sourceIndex = i;
            }
        }
        next.setSource(new SourceDTO(id.toString(), source.getTitle(),
                cutoff == Integer.MAX_VALUE ? null : cutoff, "配置 " + (char) ('A' + sourceIndex)));
        branch.setPayload(WorkbenchJson.write(next));
        workspaceMapper.updateById(branch);
        return workspaceView(branch);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceVO restore(UserContext actor, Long tenant, Long id, int version) {
        enabled(actor, tenant);
        PlaygroundWorkspaceDO preset = owned(actor, tenant, id, true);
        if (!PRESET.equals(preset.getKind())) {
            throw PlaygroundConfiguration.invalid();
        }
        VersionDTO found = payload(preset).getVersions().stream()
                .filter(item -> Integer.valueOf(version).equals(item.getNumber()))
                .findFirst().orElseThrow(PlaygroundConfiguration::invalid);
        return update(actor, tenant, id, new UpdateReqDTO(null, null, found.getConfig(),
                BigDecimal.valueOf(preset.getRevision()), Set.of("revision", "config")));
    }

    /** 只取每轮该栏最新完成尝试；停止或失败输出不进入上下文。 */
    static List<MessageDTO> context(PayloadDTO payload, List<PlaygroundAttemptDO> history,
                                    String lane, int cutoff, boolean before) {
        List<MessageDTO> result = payload.getPrefix() == null
                ? new ArrayList<>() : new ArrayList<>(payload.getPrefix());
        Map<Integer, PlaygroundAttemptDO> completed = new TreeMap<>();
        for (PlaygroundAttemptDO attempt : history) {
            if (!lane.equals(attempt.getLaneId()) || !COMPLETED.equals(attempt.getStatus())
                    || attempt.getRoundNo() > cutoff || before && attempt.getRoundNo() == cutoff) {
                continue;
            }
            PlaygroundAttemptDO prior = completed.get(attempt.getRoundNo());
            if (prior == null || prior.getAttemptNo() < attempt.getAttemptNo()) {
                completed.put(attempt.getRoundNo(), attempt);
            }
        }
        for (PlaygroundAttemptDO attempt : completed.values()) {
            result.add(new MessageDTO("user", attempt.getPrompt()));
            result.add(new MessageDTO("assistant", attempt.getReply()));
        }
        return result;
    }

    private void finish(PlaygroundAttemptDO attempt, String reply, String status, long first, long started,
                        String error, AtomicBoolean done) {
        if (done.compareAndSet(false, true)) {
            try {
                finalizer.finish(attempt, reply, status, first < 0 ? null : first,
                        System.currentTimeMillis() - started, error);
            } catch (RuntimeException ex) {
                done.set(false);
                throw ex;
            }
        }
    }

    private AttemptVO attemptView(PlaygroundAttemptDO attempt) {
        TokenUsageDO usage = usageMapper.selectOne(Wrappers.lambdaQuery(TokenUsageDO.class)
                .eq(TokenUsageDO::getTenantId, attempt.getTenantId())
                .eq(TokenUsageDO::getUserId, attempt.getOwnerUserId()).eq(TokenUsageDO::getSource, "PLAYGROUND")
                .eq(TokenUsageDO::getRequestId, attempt.getRequestId()).last("LIMIT 1"));
        ErrorVO failure = attempt.getErrorJson() == null
                ? null : WorkbenchJson.read(attempt.getErrorJson(), ErrorVO.class);
        boolean preflight = attempt.getDurationMs() == null
                || failure != null && failure.getCode() != null && failure.getCode().startsWith("A");
        boolean recent = attempt.getFinishedAt() == null || attempt.getFinishedAt().isAfter(now().minusMinutes(2));
        String settlement = usage == null ? (!preflight && recent ? "PENDING" : "UNKNOWN") : usage.getCostStatus();
        MetricsVO metrics = new MetricsVO(usage == null ? null : usage.getInputTokens(),
                usage == null ? null : usage.getOutputTokens(), usage == null ? "UNKNOWN" : usage.getUsageSource(),
                settlement, usage == null ? null : usage.getEstimatedCostFen(),
                usage == null ? null : usage.getCurrency(),
                attempt.getFirstContentMs(), attempt.getDurationMs());
        return AttemptVO.builder().attemptId(attempt.getAttemptId().toString())
                .groupId(attempt.getWorkspaceId().toString()).roundId(attempt.getRoundId())
                .roundNo(attempt.getRoundNo())
                .laneId(attempt.getLaneId()).attemptNo(attempt.getAttemptNo()).requestId(attempt.getRequestId())
                .serviceId(attempt.getServiceId().toString()).prompt(attempt.getPrompt()).reply(attempt.getReply())
                .status(attempt.getStatus()).snapshot(snapshot(attempt)).error(failure).metrics(metrics)
                .createdAt(iso(attempt.getCreateTime())).finishedAt(iso(attempt.getFinishedAt())).build();
    }

    private WorkspaceVO workspaceView(PlaygroundWorkspaceDO workspace) {
        return WorkspaceVO.builder().id(workspace.getWorkspaceId().toString())
                .title(workspace.getTitle()).description(workspace.getDescription()).kind(workspace.getKind())
                .payload(payload(workspace)).revision(workspace.getRevision())
                .generating(workspace.getGenerating() == 1)
                .createdAt(iso(workspace.getCreateTime())).updatedAt(iso(workspace.getUpdateTime())).build();
    }

    private PlaygroundWorkspaceDO owned(UserContext actor, Long tenant, Long id, boolean lock) {
        if (!lock) {
            return playgroundAccess.requireWorkspace(actor, tenant, id);
        }
        PlaygroundWorkspaceDO workspace = workspaceMapper.lock(tenant, actor.getUserId(), id);
        if (workspace == null) {
            throw new ClientException(PlaygroundErrorCodeEnum.SESSION_NOT_FOUND);
        }
        return workspace;
    }

    private PlaygroundAttemptDO ownedAttempt(UserContext actor, Long tenant, Long id) {
        PlaygroundAttemptDO attempt = attemptMapper.selectOne(Wrappers.lambdaQuery(PlaygroundAttemptDO.class)
                .eq(PlaygroundAttemptDO::getTenantId, tenant)
                .eq(PlaygroundAttemptDO::getOwnerUserId, actor.getUserId())
                .eq(PlaygroundAttemptDO::getAttemptId, id));
        if (attempt == null) {
            throw new ClientException(PlaygroundErrorCodeEnum.SESSION_NOT_FOUND);
        }
        return attempt;
    }

    private List<PlaygroundAttemptDO> attempts(UserContext actor, Long tenant, Long id) {
        return attemptMapper.selectList(Wrappers.lambdaQuery(PlaygroundAttemptDO.class)
                .eq(PlaygroundAttemptDO::getTenantId, tenant)
                .eq(PlaygroundAttemptDO::getOwnerUserId, actor.getUserId()).eq(PlaygroundAttemptDO::getWorkspaceId, id)
                .orderByAsc(PlaygroundAttemptDO::getRoundNo, PlaygroundAttemptDO::getAttemptNo,
                        PlaygroundAttemptDO::getId));
    }

    private LlmServiceDO availableModel(Long tenant, Long id) {
        LlmServiceDO model = modelMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getTenantId, tenant).eq(LlmServiceDO::getServiceId, id)
                .eq(LlmServiceDO::getDelFlag, 0).eq(LlmServiceDO::getStatus, 1));
        if (model == null) {
            return null;
        }
        try {
            modelResolver.requireBinding(model, ModelOperation.CHAT_COMPLETIONS);
            return model;
        } catch (ClientException ex) {
            return null;
        }
    }

    private LlmServiceDO model(Long tenant, Long id) {
        LlmServiceDO model = availableModel(tenant, id);
        if (model == null) {
            throw new ClientException(PlaygroundErrorCodeEnum.MODEL_UNAVAILABLE);
        }
        return model;
    }

    private CapabilitiesVO caps(LlmServiceDO model) {
        return PlaygroundConfiguration.capabilities(model,
                modelResolver.protocolFor(model, ModelOperation.CHAT_COMPLETIONS));
    }

    private void checkContext(LlmServiceDO model, RequestDTO request) {
        // 文本长度保守预检，最终 Token 上限由实际模型验证；不截断用户历史。
        long chars = request.getMessages().stream().mapToLong(message -> message.getContent().length()).sum();
        long output = request.getMaxTokens() == null ? 0L : request.getMaxTokens().longValue();
        if (model.getContextWindow() != null && chars + output > model.getContextWindow()) {
            throw new ClientException(PlaygroundErrorCodeEnum.CONTEXT_LIMIT);
        }
    }

    private PlaygroundAttemptDO attempt(PlaygroundWorkspaceDO workspace, String round, int roundNo,
                                        String lane, int number, String prompt, Long model) {
        PlaygroundAttemptDO attempt = new PlaygroundAttemptDO();
        attempt.setAttemptId(SnowflakeIdUtil.nextId());
        attempt.setRequestId(Long.toString(SnowflakeIdUtil.nextId()));
        attempt.setTenantId(workspace.getTenantId());
        attempt.setOwnerUserId(workspace.getOwnerUserId());
        attempt.setWorkspaceId(workspace.getWorkspaceId());
        attempt.setRoundId(round);
        attempt.setRoundNo(roundNo);
        attempt.setLaneId(lane);
        attempt.setAttemptNo(number);
        attempt.setServiceId(model);
        attempt.setPrompt(prompt);
        attempt.setReply("");
        attempt.setStatus(PENDING);
        attempt.setCreateTime(now());
        attempt.setUpdateTime(attempt.getCreateTime());
        return attempt;
    }

    private ErrorVO error(Throwable ex) {
        ErrorVO result = new ErrorVO();
        result.setCode(PlaygroundErrorCodeEnum.UPSTREAM_ERROR.code());
        result.setMessage(PlaygroundErrorCodeEnum.UPSTREAM_ERROR.message());
        if (ex instanceof AbstractException business) {
            if ("A000802".equals(business.getErrorCode())) {
                result.setCode(PlaygroundErrorCodeEnum.SHARED_LIMIT.code());
                result.setMessage(PlaygroundErrorCodeEnum.SHARED_LIMIT.message());
            } else {
                result.setCode(business.getErrorCode());
                result.setMessage(business.getErrorMessage());
            }
        }
        if (ex instanceof CostLimitExceededException budget) {
            result.setData(PlaygroundCostError.data(budget, budget.getRequestId()));
        }
        if (ex instanceof PlaygroundRateLimiter.LimitException limit) {
            result.setReason(limit.getReason());
            result.setRetryAfterSeconds(limit.getRetryAfterSeconds());
        }
        return result;
    }

    private static PayloadDTO payload(PlaygroundWorkspaceDO workspace) {
        return WorkbenchJson.read(workspace.getPayload(), PayloadDTO.class);
    }

    private static SnapshotDTO snapshot(PlaygroundAttemptDO attempt) {
        return WorkbenchJson.read(attempt.getSnapshot(), SnapshotDTO.class);
    }

    private static PayloadDTO groupPayload(ConfigDTO config, List<MessageDTO> prefix, SourceDTO source) {
        return PayloadDTO.builder().lanes(config.getLanes()).synced(config.getSynced()).presetId(config.getPresetId())
                .presetVersion(config.getPresetVersion()).prefix(prefix).source(source).build();
    }

    private static ConfigDTO editable(PayloadDTO payload) {
        return new ConfigDTO(payload.getLanes(), payload.getSynced(),
                payload.getPresetId(), payload.getPresetVersion());
    }

    private List<String> topology(List<LaneDTO> lanes) {
        return lanes.stream().map(lane -> lane.getLaneId() + ":" + lane.getServiceId()).toList();
    }

    private VersionDTO version(ConfigDTO config, int number) {
        return new VersionDTO(number, iso(now()), config);
    }

    private String title(String title, String fallback) {
        String value = title == null || title.isBlank() ? fallback : title.trim();
        if (value == null) {
            throw PlaygroundConfiguration.invalid();
        }
        return value.substring(0, Math.min(60, value.length()));
    }

    private String description(String description) {
        if (description != null && description.length() > 500) {
            throw PlaygroundConfiguration.invalid();
        }
        return description;
    }

    private void idle(PlaygroundWorkspaceDO workspace) {
        if (workspace.getGenerating() == 1) {
            throw new ClientException(PlaygroundErrorCodeEnum.GENERATING);
        }
    }

    private void group(PlaygroundWorkspaceDO workspace) {
        if (!GROUP.equals(workspace.getKind())) {
            throw PlaygroundConfiguration.invalid();
        }
    }

    private static void requireKind(String kind) {
        if (!GROUP.equals(kind) && !PRESET.equals(kind)) {
            throw PlaygroundConfiguration.invalid();
        }
    }

    private String uuid(String key) {
        if (key == null || key.length() != 36) {
            throw PlaygroundConfiguration.invalid();
        }
        try {
            return UUID.fromString(key).toString();
        } catch (IllegalArgumentException ex) {
            throw PlaygroundConfiguration.invalid();
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.atZone(ZONE).toOffsetDateTime().toString();
    }

    private ServerSentEvent<String> event(String name, Object value) {
        return ServerSentEvent.<String>builder().event(name).data(WorkbenchJson.write(value)).build();
    }
}
