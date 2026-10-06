package com.yonagi.verse.service.impl;

import com.yonagi.verse.service.playground.PlaygroundAccessPolicy;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.resp.PlaygroundDtos;
import com.yonagi.verse.resilience.impl.PlaygroundRateLimiter;
import com.yonagi.verse.service.LlmForwardService;
import com.yonagi.verse.service.PlaygroundService;
import com.yonagi.verse.service.forward.ModelResolver;
import com.yonagi.verse.service.pricing.PricingResolver;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import java.time.Duration;
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
    private JSONObject config() {
        return JSONObject.of("synced", false, "lanes", JSONArray.of(
                JSONObject.of("laneId", "00000000-0000-0000-0000-000000000001", "serviceId", "9", "config", JSONObject.of("system", "中文顾问", "temperature", 0.2)),
                JSONObject.of("laneId", "00000000-0000-0000-0000-000000000002", "serviceId", "9", "config", JSONObject.of("temperature", 0.8))));
    }
    private Long group() { return Long.valueOf(service.create(actor, 2L, "GROUP", JSONObject.of("config", config())).getString("id")); }

    @Test
    void modelsReturnPlaygroundLimitInsteadOfApiLimit() {
        model.setMaxOutputTokens(1024L);
        model.setProviderSettings("{\"playground\":\"{\\\"maxTokens\\\":8192}\"}");
        when(gate.models(actor, 2L)).thenReturn(new PlaygroundDtos.Models(List.of(
                new PlaygroundDtos.Model("9", model.getName(), model.getProvider(), null, model.getContextWindow()))));
        when(pricing.resolve(anyLong(), anyLong(), any())).thenReturn(PricingSnapshot.unpriced());
        assertEquals(8192L, service.models(actor, 2L).get(0).getJSONObject("capabilities").getLongValue("maxTokens"));
    }

    @Test
    void comparisonsAreIndependentAndGroupIdempotencyPreservesAttempts() {
        Long id = group(); String key = UUID.randomUUID().toString();
        service.round(actor, 2L, id, "同一个问题", key);
        assertEquals(2, history.size()); assertNotEquals(history.get(0).getRequestId(), history.get(1).getRequestId());
        service.round(actor, 2L, id, "同一个问题", key); assertEquals(2, history.size());
        assertThrows(ClientException.class, () -> service.round(actor, 2L, id, "并发下一轮", UUID.randomUUID().toString()));
        assertEquals(0.2, JSON.parseObject(history.get(0).getSnapshot()).getJSONObject("request").getDoubleValue("temperature"));
        verifyNoInteractions(forward, limits);
        history.get(0).setStatus("COMPLETED"); history.get(0).setReply("A 的历史"); history.get(1).setStatus("STOPPED"); store.get(id).setGenerating(0);
        service.round(actor, 2L, id, "第二个问题", UUID.randomUUID().toString());
        JSONArray a = JSON.parseObject(history.get(2).getSnapshot()).getJSONObject("request").getJSONArray("messages");
        JSONArray b = JSON.parseObject(history.get(3).getSnapshot()).getJSONObject("request").getJSONArray("messages");
        assertTrue(a.toJSONString().contains("A 的历史")); assertFalse(b.toJSONString().contains("A 的历史")); assertEquals(1, b.size());
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
        assertEquals("COMPLETED", first.getStatus()); assertEquals("完整回答", first.getReply()); assertNotNull(first.getFirstContentMs());
        assertThrows(ClientException.class, () -> service.stream(actor, 2L, first.getAttemptId()));
        assertThrows(ClientException.class, () -> stream.collectList().block(Duration.ofSeconds(3)));
        verify(forward, times(1)).playgroundChatStream(eq(actor), eq(9L), anyList(), anyMap(), anyString(), any());
        assertEquals("PENDING", history.get(1).getStatus());
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
                    .contentType("application/json").content("{\"config\":" + config().toJSONString() + "}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.payload.lanes[0].serviceId").value("9"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/tenants/2/playground/workbench/groups")
                    .contentType("application/json").content("{\"config\":" + config().toJSONString() + ",\"history\":[{\"role\":\"system\",\"content\":\"伪造\"}]}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        } finally { com.yonagi.verse.common.security.UserContextHolder.clear(); }
    }

    @Test
    void retryUsesOriginalSnapshotAndOwnIdempotencyKey() {
        Long id = group(); service.round(actor, 2L, id, "问题", UUID.randomUUID().toString());
        history.forEach(a -> a.setStatus("FAILED")); store.get(id).setGenerating(0);
        PlaygroundAttemptDO old = history.get(0); String snapshot = old.getSnapshot();
        JSONObject changed = config(); changed.getJSONArray("lanes").getJSONObject(0).getJSONObject("config").put("temperature", 1.9);
        service.update(actor, 2L, id, JSONObject.of("revision", 1, "config", changed));
        String key = UUID.randomUUID().toString(); JSONObject retry = service.retry(actor, 2L, old.getAttemptId(), key);
        assertEquals(3, history.size()); assertEquals(snapshot, old.getSnapshot());
        assertEquals(JSON.parseObject(snapshot).getJSONObject("request"), retry.getJSONObject("snapshot").getJSONObject("request"));
        assertEquals(retry.getString("attemptId"), service.retry(actor, 2L, old.getAttemptId(), key).getString("attemptId")); assertEquals(3, history.size());
    }

    @Test
    void presetsRestoreAppendsImmutableVersionAndOtherOwnersCannotRead() {
        Long id = Long.valueOf(service.create(actor, 2L, "PRESET", JSONObject.of("title", "我的配置", "config", config())).getString("id"));
        service.update(actor, 2L, id, JSONObject.of("revision", 1, "config", config()));
        JSONObject restored = service.restore(actor, 2L, id, 1); JSONArray versions = restored.getJSONObject("payload").getJSONArray("versions");
        assertEquals(3, versions.size()); assertEquals(1, versions.getJSONObject(0).getIntValue("number")); assertEquals(3, versions.getJSONObject(2).getIntValue("number"));
        assertThrows(ClientException.class, () -> service.detail(new UserContext().setUserId(8L).setCurrentTenantId(2L), 2L, id));
    }

    @Test
    void regenerateForkExcludesTargetQuestionAndRetainsSource() {
        Long id = group(); service.round(actor, 2L, id, "第一轮", UUID.randomUUID().toString());
        history.forEach(a -> { a.setStatus("COMPLETED"); a.setReply("第一轮回答"); }); store.get(id).setGenerating(0);
        service.round(actor, 2L, id, "目标问题", UUID.randomUUID().toString());
        history.forEach(a -> { a.setStatus("COMPLETED"); a.setReply("保留原回答"); }); store.get(id).setGenerating(0);
        JSONObject branch = service.fork(actor, 2L, id, JSONObject.of("attemptId", history.get(2).getAttemptId().toString(), "before", true));
        String prefix = branch.getJSONObject("payload").getJSONArray("prefix").toJSONString();
        assertFalse(prefix.contains("目标问题")); assertTrue(prefix.contains("第一轮")); assertEquals(4, history.size());
        assertEquals("保留原回答", history.get(2).getReply()); assertNotEquals(id.toString(), branch.getString("id"));
    }
}
