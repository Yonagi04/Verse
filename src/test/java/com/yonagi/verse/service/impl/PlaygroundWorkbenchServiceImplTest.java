package com.yonagi.verse.service.impl;

import com.yonagi.verse.service.playground.PlaygroundAccessPolicy;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.PlaygroundErrorCodeEnum;
import com.yonagi.verse.common.enums.PricePeriodType;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.resp.PlaygroundDtos;
import com.yonagi.verse.dto.playground.WorkbenchTypes.AttemptVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.ConfigDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.LaneDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.MessageDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.ModelVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.RoundVO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.RunConfigDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.VersionDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.WorkspaceVO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.CreateReqDTO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.ForkReqDTO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.UpdateReqDTO;
import com.yonagi.verse.resilience.impl.PlaygroundRateLimiter;
import com.yonagi.verse.service.LlmForwardService;
import com.yonagi.verse.service.PlaygroundService;
import com.yonagi.verse.service.forward.ModelResolver;
import com.yonagi.verse.service.playground.WorkbenchJson;
import com.yonagi.verse.service.pricing.PricingResolver;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.stream.Stream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaygroundWorkbenchServiceImplTest {
    private final com.yonagi.verse.dao.mapper.TenantMapper tenants = mock(com.yonagi.verse.dao.mapper.TenantMapper.class);
    private final com.yonagi.verse.dao.mapper.UserTenantMapper memberships = mock(com.yonagi.verse.dao.mapper.UserTenantMapper.class);
    private final PlaygroundService gate = mock(PlaygroundService.class);
    private final PlaygroundWorkspaceMapper workspaces = mock(PlaygroundWorkspaceMapper.class);
    private final PlaygroundAttemptMapper attemptMapper = mock(PlaygroundAttemptMapper.class);
    private final LlmServiceMapper models = mock(LlmServiceMapper.class);
    private final TokenUsageMapper usage = mock(TokenUsageMapper.class);
    private final ModelResolver resolver = mock(ModelResolver.class);
    private final PlaygroundRateLimiter limits = mock(PlaygroundRateLimiter.class);
    private final LlmForwardService forward = mock(LlmForwardService.class);
    private final PlaygroundAttemptFinalizer finalizer = mock(PlaygroundAttemptFinalizer.class);
    private final PricingResolver pricing = mock(PricingResolver.class);
    private final PlaygroundAccessPolicy playgroundAccess = new PlaygroundAccessPolicy(
            new TenantAccessPolicy(tenants, memberships), workspaces);
    private final PlaygroundWorkbenchServiceImpl service = new PlaygroundWorkbenchServiceImpl(gate,
                playgroundAccess,
                workspaces,
                attemptMapper,
                models,
                usage,
                resolver,
                pricing,
                limits,
                forward,
                finalizer);
    private final UserContext actor = new UserContext().setUserId(7L).setCurrentTenantId(2L);
    private final Map<Long, PlaygroundWorkspaceDO> store = new HashMap<>();
    private final List<PlaygroundAttemptDO> history = new ArrayList<>();
    private LlmServiceDO model;

    @BeforeEach
    void setup() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        for (Class<?> type : List.of(PlaygroundWorkspaceDO.class, PlaygroundAttemptDO.class, LlmServiceDO.class, TokenUsageDO.class, com.yonagi.verse.dao.entity.TenantDO.class, com.yonagi.verse.dao.entity.UserTenantDO.class)) TableInfoHelper.initTableInfo(assistant, type);
        com.yonagi.verse.dao.entity.TenantDO tenant = new com.yonagi.verse.dao.entity.TenantDO(); tenant.setPlaygroundEnabled(1);
        when(tenants.selectOne(any())).thenReturn(tenant);
        when(memberships.selectOne(any())).thenReturn(new com.yonagi.verse.dao.entity.UserTenantDO());
        when(workspaces.insert(any())).thenAnswer(i -> { PlaygroundWorkspaceDO w = i.getArgument(0); store.put(w.getWorkspaceId(), w); return 1; });
        when(workspaces.lock(anyLong(), anyLong(), anyLong())).thenAnswer(i -> {
            PlaygroundWorkspaceDO w = store.get(i.getArgument(2));
            return w != null && w.getTenantId().equals(i.getArgument(0)) && w.getOwnerUserId().equals(i.getArgument(1)) && w.getDelFlag() == 0 ? w : null;
        });
        when(workspaces.selectOne(any())).thenAnswer(i -> store.values().stream().filter(w -> matches(i.getArgument(0), w.getWorkspaceId(), w.getTenantId(), w.getOwnerUserId())).findFirst().orElse(null));
        when(attemptMapper.insert(any())).thenAnswer(i -> { history.add(i.getArgument(0)); return 1; });
        when(attemptMapper.selectList(any())).thenAnswer(i -> history.stream().filter(a -> matches(i.getArgument(0), a.getWorkspaceId(), a.getTenantId(), a.getOwnerUserId())).toList());
        when(attemptMapper.selectOne(any())).thenAnswer(i -> history.stream().filter(a -> matches(i.getArgument(0), a.getAttemptId(), a.getTenantId(), a.getOwnerUserId())).findFirst().orElse(null));
        when(attemptMapper.update(any())).thenAnswer(i -> {
            AbstractWrapper<?, ?, ?> q = i.getArgument(0); q.getSqlSegment(); Collection<Object> p = q.getParamNameValuePairs().values();
            for (PlaygroundAttemptDO a : history) if (p.contains(a.getAttemptId()) && "PENDING".equals(a.getStatus())) { a.setStatus("STREAMING"); return 1; }
            return 0;
        });
        doAnswer(i -> {
            PlaygroundAttemptDO a = i.getArgument(0); a.setReply(i.getArgument(1)); a.setStatus(i.getArgument(2));
            a.setFirstContentMs(i.getArgument(3)); a.setDurationMs(i.getArgument(4)); a.setErrorJson(i.getArgument(5));
            store.get(a.getWorkspaceId()).setGenerating(history.stream().anyMatch(x -> Set.of("PENDING", "STREAMING").contains(x.getStatus())) ? 1 : 0); return null;
        }).when(finalizer).finish(any(), anyString(), anyString(), nullable(Long.class), nullable(Long.class), nullable(String.class));
        model = new LlmServiceDO(); model.setServiceId(9L); model.setTenantId(2L); model.setName("tenant-alias"); model.setProvider("custom"); model.setContextWindow(10000L);
        model.setProviderSettings("{\"playground\":\"{\\\"temperature\\\":{\\\"min\\\":0,\\\"max\\\":2},\\\"maxTokens\\\":1000}\"}");
        when(models.selectOne(any())).thenReturn(model); when(resolver.protocolFor(any(), any())).thenReturn(UpstreamProtocol.OPENAI_COMPAT);
    }

    private boolean matches(AbstractWrapper<?, ?, ?> q, Long resource, Long tenant, Long owner) {
        String sql = q.getSqlSegment(); Collection<Object> p = q.getParamNameValuePairs().values();
        assertTrue(sql.contains("tenant_id") && (sql.contains("owner_user_id") || sql.contains("user_id")));
        return p.contains(resource) && p.contains(tenant) && p.contains(owner);
    }
    private ConfigDTO config() {
        return new ConfigDTO(List.of(
                new LaneDTO("00000000-0000-0000-0000-000000000001", "9",
                        new RunConfigDTO("中文顾问", new BigDecimal("0.2"), null, null)),
                new LaneDTO("00000000-0000-0000-0000-000000000002", "9",
                        new RunConfigDTO(null, new BigDecimal("0.8"), null, null))), false, null, null);
    }

    private Long group() {
        return Long.valueOf(service.create(actor, 2L, "GROUP", new CreateReqDTO(null, null, config())).getId());
    }

    @Test
    void modelsReturnPlaygroundLimitInsteadOfApiLimit() {
        model.setMaxOutputTokens(1024L);
        model.setProviderSettings("{\"playground\":\"{\\\"maxTokens\\\":8192}\"}");
        when(gate.models(actor, 2L)).thenReturn(new PlaygroundDtos.Models(List.of(
                new PlaygroundDtos.Model("9", model.getName(), model.getProvider(), null, model.getContextWindow()))));
        when(pricing.resolve(anyLong(), anyLong(), any())).thenReturn(PricingSnapshot.unpriced());
        assertEquals(8192L, service.models(actor, 2L).getFirst().getCapabilities().getMaxTokens());
    }

    @Test
    void comparisonsAreIndependentAndGroupIdempotencyPreservesAttempts() {
        Long id = group(); String key = UUID.randomUUID().toString();
        service.round(actor, 2L, id, "同一个问题", key);
        assertEquals(2, history.size()); assertNotEquals(history.get(0).getRequestId(), history.get(1).getRequestId());
        service.round(actor, 2L, id, "同一个问题", key); assertEquals(2, history.size());
        assertThrows(ClientException.class, () -> service.round(actor, 2L, id, "并发下一轮", UUID.randomUUID().toString()));
        assertEquals(0.2D, WorkbenchJson.tree(history.getFirst().getSnapshot()).path("request").path("temperature")
                .doubleValue());
        verifyNoInteractions(forward, limits);
        history.get(0).setStatus("COMPLETED"); history.get(0).setReply("A 的历史"); history.get(1).setStatus("STOPPED"); store.get(id).setGenerating(0);
        service.round(actor, 2L, id, "第二个问题", UUID.randomUUID().toString());
        JsonNode a = WorkbenchJson.tree(history.get(2).getSnapshot()).path("request").path("messages");
        JsonNode b = WorkbenchJson.tree(history.get(3).getSnapshot()).path("request").path("messages");
        assertTrue(a.toString().contains("A 的历史"));
        assertFalse(b.toString().contains("A 的历史"));
        assertEquals(1, b.size());
    }

    @Test
    void unsupportedConfigurationBlocksBeforeAnyAttempts() {
        model.setProviderSettings(null); Long id = group();
        assertThrows(ClientException.class, () -> service.round(actor, 2L, id, "问题", UUID.randomUUID().toString()));
        assertTrue(history.isEmpty()); assertEquals(0, store.get(id).getGenerating()); verifyNoInteractions(forward);
    }

    @Test
    void streamOnlyClaimsOnceAndPreservesFirstContentLatency() {
        Long id = group(); service.round(actor, 2L, id, "问题", UUID.randomUUID().toString());
        when(forward.playgroundChatStream(any(), anyLong(), anyList(), anyMap(), anyString(), any())).thenReturn(Flux.just(
                ServerSentEvent.<String>builder("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}").build(),
                ServerSentEvent.<String>builder("{\"choices\":[{\"delta\":{\"content\":\"完整回答\"}}]}").build()));
        PlaygroundAttemptDO first = history.get(0);
        var stream = service.stream(actor, 2L, first.getAttemptId());
        var events = stream.collectList().block(Duration.ofSeconds(3));
        assertEquals(List.of("accepted", "delta", "completed"), events.stream().map(ServerSentEvent::event).toList());
        JsonNode accepted = WorkbenchJson.tree(events.getFirst().data());
        assertEquals(first.getAttemptId().toString(), accepted.path("attemptId").textValue());
        assertEquals(first.getRequestId(), accepted.path("requestId").textValue());
        assertEquals("完整回答", WorkbenchJson.tree(events.get(1).data()).path("text").textValue());
        JsonNode completed = WorkbenchJson.tree(events.getLast().data());
        assertEquals("COMPLETED", completed.path("status").textValue());
        assertEquals(1000L, completed.path("snapshot").path("request").path("max_tokens").longValue());
        assertFalse(completed.path("snapshot").path("request").has("maxTokens"));
        assertTrue(completed.path("metrics").path("durationMs").isIntegralNumber());
        assertEquals("COMPLETED", first.getStatus()); assertEquals("完整回答", first.getReply()); assertNotNull(first.getFirstContentMs());
        assertThrows(ClientException.class, () -> service.stream(actor, 2L, first.getAttemptId()));
        assertThrows(ClientException.class, () -> stream.collectList().block(Duration.ofSeconds(3)));
        verify(forward, times(1)).playgroundChatStream(eq(actor), eq(9L), anyList(), anyMap(), anyString(), any());
        assertEquals("PENDING", history.get(1).getStatus());
    }

    @Test void budgetRejectionFinishesOnlyOneLaneBeforeHeadersAndRetainsStructuredError() {
        Long id=group(); service.round(actor,2L,id,"保留问题",UUID.randomUUID().toString());
        var budget=new com.yonagi.verse.service.budget.CostLimitExceededException(new com.yonagi.verse.service.budget.CostBudgetDecision(
                List.of(new com.yonagi.verse.dto.resp.ApiKeyCostStatusRespDTO.Hit("LLM","DAY","2026-10-12T00:00:00+08:00")),false));
        when(forward.playgroundChatStream(any(),anyLong(),anyList(),anyMap(),anyString(),any())).thenThrow(budget)
                .thenReturn(Flux.just(ServerSentEvent.<String>builder("{\"choices\":[{\"delta\":{\"content\":\"另一栏成功\"}}]}").build()));
        assertSame(budget,assertThrows(com.yonagi.verse.service.budget.CostLimitExceededException.class,()->service.stream(actor,2L,history.get(0).getAttemptId())));
        assertEquals("FAILED",history.get(0).getStatus()); assertEquals("PENDING",history.get(1).getStatus());
        JsonNode error = WorkbenchJson.tree(history.getFirst().getErrorJson());
        assertEquals("A000809", error.path("code").textValue());
        assertEquals(history.getFirst().getRequestId(), error.path("data").path("requestId").textValue());
        assertFalse(error.path("data").has("usedCostFen"));
        service.stream(actor,2L,history.get(1).getAttemptId()).collectList().block(Duration.ofSeconds(3));
        assertEquals("COMPLETED",history.get(1).getStatus()); assertEquals(0,store.get(id).getGenerating());
    }

    @Test
    void stoppingOneLaneCancelsOnlyItsStreamAndKeepsPartialOutput() throws Exception {
        Long id = group(); service.round(actor, 2L, id, "问题", UUID.randomUUID().toString());
        var subscribed = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(forward.playgroundChatStream(any(), anyLong(), anyList(), anyMap(), anyString(), any())).thenReturn(Flux.just(
                ServerSentEvent.<String>builder("{\"choices\":[{\"delta\":{\"content\":\"部分内容\"}}]}").build())
                .concatWith(Flux.never()).doOnNext(chunk -> subscribed.countDown()).doOnCancel(() -> cancelled.set(true)));
        var future = service.stream(actor, 2L, history.get(0).getAttemptId()).collectList().toFuture();
        assertTrue(subscribed.await(2, java.util.concurrent.TimeUnit.SECONDS));
        service.stop(actor, 2L, history.get(0).getAttemptId()); future.get(3, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(cancelled.get()); assertEquals("STOPPED", history.get(0).getStatus());
        assertEquals("部分内容", history.get(0).getReply()); assertEquals("PENDING", history.get(1).getStatus());
    }

    @Test
    void httpConfigurationIsParsedWithoutAcceptingClientHistory() throws Exception {
        com.yonagi.verse.common.security.UserContextHolder.set(actor);
        try {
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new com.yonagi.verse.controller.PlaygroundWorkbenchController(service)).build();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/tenants/2/playground/workbench/groups")
                    .contentType("application/json").content("{\"config\":" + WorkbenchJson.write(config()) + "}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.payload.lanes[0].serviceId").value("9"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/tenants/2/playground/workbench/groups")
                    .contentType("application/json").content("{\"config\":" + WorkbenchJson.write(config())
                            + ",\"history\":[{\"role\":\"system\",\"content\":\"伪造\"}]}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        } finally { com.yonagi.verse.common.security.UserContextHolder.clear(); }
    }

    @Test
    void retryUsesOriginalSnapshotAndOwnIdempotencyKey() {
        Long id = group(); service.round(actor, 2L, id, "问题", UUID.randomUUID().toString());
        history.forEach(a -> a.setStatus("FAILED")); store.get(id).setGenerating(0);
        PlaygroundAttemptDO old = history.get(0); String snapshot = old.getSnapshot();
        ConfigDTO changed = config();
        changed.getLanes().getFirst().getConfig().setTemperature(new BigDecimal("1.9"));
        service.update(actor, 2L, id, new UpdateReqDTO(null, null, changed, BigDecimal.ONE,
                Set.of("revision", "config")));
        String key = UUID.randomUUID().toString();
        AttemptVO retry = service.retry(actor, 2L, old.getAttemptId(), key);
        assertEquals(3, history.size()); assertEquals(snapshot, old.getSnapshot());
        assertEquals(WorkbenchJson.tree(snapshot).path("request"),
                WorkbenchJson.tree(WorkbenchJson.write(retry.getSnapshot().getRequest())));
        assertEquals(retry.getAttemptId(), service.retry(actor, 2L, old.getAttemptId(), key).getAttemptId());
        assertEquals(3, history.size());
    }

    @Test
    void presetsRestoreAppendsImmutableVersionAndOtherOwnersCannotRead() {
        Long id = Long.valueOf(service.create(actor, 2L, "PRESET", new CreateReqDTO("我的配置", null, config())).getId());
        service.update(actor, 2L, id, new UpdateReqDTO(null, null, config(), BigDecimal.ONE,
                Set.of("revision", "config")));
        WorkspaceVO restored = service.restore(actor, 2L, id, 1);
        List<VersionDTO> versions = restored.getPayload().getVersions();
        assertEquals(List.of(1, 2, 3), versions.stream().map(VersionDTO::getNumber).toList());
        assertEquals(versions.getFirst().getConfig(), versions.getLast().getConfig());
        assertThrows(ClientException.class, () -> service.detail(new UserContext().setUserId(8L).setCurrentTenantId(2L), 2L, id));
    }

    @Test
    void regenerateForkExcludesTargetQuestionAndRetainsSource() {
        Long id = group(); service.round(actor, 2L, id, "第一轮", UUID.randomUUID().toString());
        history.forEach(a -> { a.setStatus("COMPLETED"); a.setReply("第一轮回答"); }); store.get(id).setGenerating(0);
        service.round(actor, 2L, id, "目标问题", UUID.randomUUID().toString());
        history.subList(2, 4).forEach(a -> { a.setStatus("COMPLETED"); a.setReply("保留原回答"); });
        store.get(id).setGenerating(0);
        WorkspaceVO branch = service.fork(actor, 2L, id,
                new ForkReqDTO(history.get(2).getAttemptId().toString(), null, true, null, null));
        List<String> prefix = branch.getPayload().getPrefix().stream().map(MessageDTO::getContent).toList();
        assertEquals(List.of("第一轮", "第一轮回答"), prefix);
        assertEquals(id.toString(), branch.getPayload().getSource().getGroupId());
        assertEquals(4, history.size());
        assertEquals("保留原回答", history.get(2).getReply());
        assertNotEquals(id.toString(), branch.getId());
    }

    @Test
    void cachedModelMetadataRemainsUnpricedWhileEachViewUsesCurrentPrice() {
        when(gate.models(actor, 2L)).thenReturn(new PlaygroundDtos.Models(List.of(
                new PlaygroundDtos.Model("9", "模型", "custom", null, 10000L))));
        List<ModelVO> metadata = service.modelMetadata(actor, 2L);
        when(pricing.resolve(eq(2L), eq(9L), any())).thenReturn(
                new PricingSnapshot(1L, BillingMode.TOKEN, "CNY", PricePeriodType.BASE, null,
                        BigDecimal.ONE, null, null, null, null, null),
                new PricingSnapshot(1L, BillingMode.TOKEN, "CNY", PricePeriodType.PEAK, null,
                        BigDecimal.TEN, null, null, null, null, null));
        ModelVO base = service.withCurrentPrices(2L, metadata).getFirst();
        ModelVO peak = service.withCurrentPrices(2L, metadata).getFirst();
        assertEquals(BigDecimal.ONE, base.getPricing().getInputPriceFen());
        assertEquals(BigDecimal.TEN, peak.getPricing().getInputPriceFen());
        assertNull(metadata.getFirst().getPricing());
    }

    @Test
    void historicalPayloadAndExplicitDescriptionClearRetainTheirSemantics() {
        Long id = Long.valueOf(service.create(actor, 2L, "GROUP",
                new CreateReqDTO("旧会话", "保留描述", config())).getId());
        PlaygroundWorkspaceDO stored = store.get(id);
        stored.setPayload("""
                {"lanes":[{"laneId":"00000000-0000-0000-0000-000000000001","serviceId":"9",
                "config":{"temperature":0.25,"maxTokens":1000}}],"synced":true,
                "presetId":"9007199254740993","presetVersion":2,
                "prefix":[{"role":"user","content":"已有历史"}],
                "source":{"groupId":"99","title":"原会话","roundNo":1,"laneLabel":"配置 A"}}
                """);
        when(workspaces.selectCount(any())).thenReturn(1L);
        WorkspaceVO existing = service.detail(actor, 2L, id);
        assertTrue(existing.getSourceAvailable());
        assertEquals("9007199254740993", existing.getPayload().getPresetId());
        assertEquals(new BigDecimal("0.25"), existing.getPayload().getLanes().getFirst().getConfig().getTemperature());
        WorkspaceVO renamed = service.update(actor, 2L, id,
                new UpdateReqDTO("改名", null, null, BigDecimal.ONE, Set.of("title", "revision")));
        assertEquals("保留描述", renamed.getDescription());
        WorkspaceVO cleared = service.update(actor, 2L, id,
                new UpdateReqDTO(null, null, null, BigDecimal.valueOf(2), Set.of("description", "revision")));
        assertNull(cleared.getDescription());
        assertEquals("已有历史", cleared.getPayload().getPrefix().getFirst().getContent());
        assertEquals("99", cleared.getPayload().getSource().getGroupId());
    }

    @Test
    void preciseFractionCannotStartRoundAfterStoredConfigRoundTrip() {
        ConfigDTO config = config();
        config.getLanes().getFirst().getConfig().setMaxTokens(new BigDecimal("1.00000000000000000001"));
        Long id = Long.valueOf(service.create(actor, 2L, "GROUP", new CreateReqDTO(null, null, config)).getId());
        ClientException error = assertThrows(ClientException.class,
                () -> service.round(actor, 2L, id, "问题", UUID.randomUUID().toString()));
        assertEquals(PlaygroundErrorCodeEnum.INVALID_CONFIG.code(), error.getErrorCode());
        assertTrue(history.isEmpty());
        assertEquals(0, store.get(id).getGenerating());
    }

    @Test
    void corruptedStoredPayloadFailsWithoutLoggingTheRawValue() {
        Long id = group();
        store.get(id).setPayload("{\"lanes\":\"private-prompt-marker\"}");
        ServerException error = assertThrows(ServerException.class, () -> service.detail(actor, 2L, id));
        assertFalse(error.getErrorMessage().contains("private-prompt-marker"));
        assertFalse(error.getCause().getMessage().contains("private-prompt-marker"));
        assertEquals("MismatchedInputException", error.getCause().getMessage());
    }

    @ParameterizedTest
    @MethodSource("invalidStreamChunks")
    void invalidProviderChunkKeepsSafeTypedFailure(String chunk, PlaygroundErrorCodeEnum expected) {
        Long id = group();
        service.round(actor, 2L, id, "问题", UUID.randomUUID().toString());
        when(forward.playgroundChatStream(any(), anyLong(), anyList(), anyMap(), anyString(), any()))
                .thenReturn(Flux.just(ServerSentEvent.builder(chunk).build()));
        PlaygroundAttemptDO attempt = history.getFirst();
        var events = service.stream(actor, 2L, attempt.getAttemptId()).collectList().block(Duration.ofSeconds(3));
        assertEquals(List.of("accepted", "error"), events.stream().map(ServerSentEvent::event).toList());
        JsonNode failure = WorkbenchJson.tree(events.getLast().data());
        assertEquals("FAILED", failure.path("status").textValue());
        assertEquals(expected.code(), failure.path("error").path("code").textValue());
        assertFalse(events.getLast().data().contains("private-prompt-marker"));
    }

    private static Stream<Arguments> invalidStreamChunks() {
        return Stream.of(
                Arguments.of("{\"error\":\"private-prompt-marker\"}", PlaygroundErrorCodeEnum.UPSTREAM_ERROR),
                Arguments.of("{\"private-prompt-marker\":", PlaygroundErrorCodeEnum.UPSTREAM_ERROR),
                Arguments.of("[]", PlaygroundErrorCodeEnum.UPSTREAM_ERROR),
                Arguments.of("{\"choices\":[{\"delta\":{\"tool_calls\":[]}}]}",
                        PlaygroundErrorCodeEnum.UNSUPPORTED_RESPONSE),
                Arguments.of("{\"choices\":[{\"delta\":{\"content\":{}}}]}",
                        PlaygroundErrorCodeEnum.UNSUPPORTED_RESPONSE));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {" ", "旧会话"})
    void listMapsStoredResourcesWithOrWithoutKeyword(String keyword) {
        Long id = group();
        when(workspaces.selectList(any())).thenReturn(List.of(store.get(id)));
        List<WorkspaceVO> result = service.list(actor, 2L, "GROUP", keyword);
        assertEquals(List.of(id.toString()), result.stream().map(WorkspaceVO::getId).toList());
        assertEquals("9", result.getFirst().getPayload().getLanes().getFirst().getServiceId());
        assertNull(result.getFirst().getAttempts());
    }

    @Test
    void detailMapsSettledUsageAndRetainsAttemptSnapshot() {
        Long id = group();
        service.round(actor, 2L, id, "问题", UUID.randomUUID().toString());
        TokenUsageDO settled = new TokenUsageDO();
        settled.setInputTokens(10L);
        settled.setOutputTokens(3L);
        settled.setUsageSource("EXACT");
        settled.setCostStatus("CALCULATED");
        settled.setEstimatedCostFen(new BigDecimal("1.25"));
        settled.setCurrency("CNY");
        when(usage.selectOne(any())).thenReturn(settled);
        WorkspaceVO detail = service.detail(actor, 2L, id);
        AttemptVO view = detail.getAttempts().getFirst();
        assertTrue(detail.getGenerating());
        assertEquals(history.getFirst().getAttemptId().toString(), view.getAttemptId());
        assertEquals(10L, view.getMetrics().getInputTokens());
        assertEquals(3L, view.getMetrics().getOutputTokens());
        assertEquals("EXACT", view.getMetrics().getEvidence());
        assertEquals("CALCULATED", view.getMetrics().getCostStatus());
        assertEquals(new BigDecimal("1.25"), view.getMetrics().getCostFen());
        assertEquals("CNY", view.getMetrics().getCurrency());
    }

    @Test
    void legacySnapshotForwardsOnlyDeclaredParametersAndIgnoresEmptyFrames() {
        model.setContextWindow(null);
        model.setProviderSettings("{\"playground\":{\"topP\":{\"min\":0,\"max\":1}}}");
        ConfigDTO config = new ConfigDTO(List.of(new LaneDTO("00000000-0000-0000-0000-000000000001",
                "9", new RunConfigDTO(null, null, new BigDecimal("0.5"), null))), true, null, null);
        Long id = Long.valueOf(service.create(actor, 2L, "GROUP", new CreateReqDTO("已有标题", null, config)).getId());
        service.round(actor, 2L, id, "问题", UUID.randomUUID().toString());
        PlaygroundAttemptDO attempt = history.getFirst();
        attempt.setSnapshot("""
                {"request":{"model":"tenant-alias","stream":true,"top_p":0.5,
                "messages":[{"role":"user","content":"问题"}]},
                "config":{"topP":0.5},"modelName":"tenant-alias","provider":"custom"}
                """);
        when(forward.playgroundChatStream(any(), anyLong(), anyList(), anyMap(), anyString(), any()))
                .thenReturn(Flux.just(ServerSentEvent.<String>builder().build(),
                        ServerSentEvent.builder(" ").build(), ServerSentEvent.builder("null").build(),
                        ServerSentEvent.builder("{}").build(), ServerSentEvent.builder("{\"choices\":[]}").build(),
                        ServerSentEvent.builder("{\"choices\":[{\"delta\":{\"content\":\"\"}}]}").build(),
                        ServerSentEvent.builder("{\"choices\":[{\"delta\":{\"content\":\"正文\"}}]}").build(),
                        ServerSentEvent.builder("[DONE]").build()));
        var events = service.stream(actor, 2L, attempt.getAttemptId()).collectList().block(Duration.ofSeconds(3));
        assertEquals(List.of("accepted", "delta", "completed"), events.stream().map(ServerSentEvent::event).toList());
        assertEquals("正文", attempt.getReply());
        verify(forward).playgroundChatStream(eq(actor), eq(9L), anyList(),
                eq(Map.of("top_p", new BigDecimal("0.5"))), eq(attempt.getRequestId()), any());
    }

    @Test
    void unavailableModelRetainsTypedInputSnapshotAndFailsOnlyItsAttempts() {
        Long id = group();
        when(models.selectOne(any())).thenReturn(null);
        RoundVO round = service.round(actor, 2L, id, "问题", UUID.randomUUID().toString());
        assertEquals(2, round.getAttempts().size());
        for (AttemptVO attempt : round.getAttempts()) {
            assertEquals("FAILED", attempt.getStatus());
            assertEquals(PlaygroundErrorCodeEnum.MODEL_UNAVAILABLE.code(), attempt.getError().getCode());
            assertEquals("问题", attempt.getSnapshot().getRequest().getMessages().getLast().getContent());
            assertNull(attempt.getSnapshot().getProvider());
        }
        verifyNoInteractions(forward);
    }

    @Test
    void forkAtEndInheritsTypedConfigAndSourceWithoutCutoff() {
        Long id = group();
        String lane = config().getLanes().getFirst().getLaneId();
        service.round(actor, 2L, id, "问题", UUID.randomUUID().toString());
        history.getFirst().setStatus("COMPLETED");
        history.getFirst().setReply("已保存");
        store.get(id).setGenerating(0);
        WorkspaceVO branch = service.fork(actor, 2L, id, new ForkReqDTO(null, lane, null, null, null));
        assertEquals(config().getLanes(), branch.getPayload().getLanes());
        assertEquals(List.of("问题", "已保存"),
                branch.getPayload().getPrefix().stream().map(MessageDTO::getContent).toList());
        assertNull(branch.getPayload().getSource().getRoundNo());
        assertEquals(id.toString(), branch.getPayload().getSource().getGroupId());
    }
}
