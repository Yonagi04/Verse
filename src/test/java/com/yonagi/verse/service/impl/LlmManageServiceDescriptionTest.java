package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.event.TenantActivityDraft;
import com.yonagi.verse.common.enums.TenantActivityType;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.LlmServiceCapabilityMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.LlmServiceAddReqDTO;
import com.yonagi.verse.dto.req.LlmServiceUpdateReqDTO;
import com.yonagi.verse.dto.req.LlmServiceRemoveReqDTO;
import com.yonagi.verse.dto.resp.LlmServiceListRespDTO;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.pricing.LlmMetadataService;
import com.yonagi.verse.service.pricing.PricingConfigurationService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class LlmManageServiceDescriptionTest {

    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    private final UserTenantService userTenantService = mock(UserTenantService.class);
    private final AesUtil aesUtil = mock(AesUtil.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    private final LlmServiceMapper llmServiceMapper = mock(LlmServiceMapper.class);
    private final LlmServiceCapabilityMapper capabilityMapper = mock(LlmServiceCapabilityMapper.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final LlmMetadataService metadataService = mock(LlmMetadataService.class);
    private final TenantActivityRecorder activityRecorder = mock(TenantActivityRecorder.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private LlmManageServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "llm-description-test"),
                LlmServiceDO.class
        );
        service = new LlmManageServiceImpl(
                tenantMapper,
                userTenantService,
                aesUtil,
                redisTemplate,
                mock(UserMapper.class),
                jwtUtil,
                redissonClient,
                metadataService,
                mock(PricingConfigurationService.class),
                activityRecorder,
                capabilityMapper
        );
        ReflectionTestUtils.setField(service, "baseMapper", llmServiceMapper);
        when(tenantMapper.selectOne(any())).thenReturn(new TenantDO());
        when(userTenantService.isUserJoinedTenant(1L, 2L)).thenReturn(true);
    }

    @Test
    void addTrimsAndPersistsDescription() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(any())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(llmServiceMapper.selectCount(any())).thenReturn(0L);
        when(llmServiceMapper.insert(any())).thenReturn(1);
        when(aesUtil.encrypt("secret")).thenReturn("encrypted");

        LlmServiceAddReqDTO request = new LlmServiceAddReqDTO();
        request.setName("openai.gpt-test");
        request.setProvider("openai");
        request.setApiUrl("https://example.invalid/v1");
        request.setApiKey("secret");
        request.setModelName("gpt-test");
        request.setDescription("  通用测试模型  ");

        assertTrue(service.addLlmService(1L, 2L, request));

        ArgumentCaptor<LlmServiceDO> captor = ArgumentCaptor.forClass(LlmServiceDO.class);
        verify(llmServiceMapper).insert(captor.capture());
        assertEquals("通用测试模型", captor.getValue().getDescription());
        verify(lock).unlock();
        ArgumentCaptor<TenantActivityDraft> activity=ArgumentCaptor.forClass(TenantActivityDraft.class);
        verify(activityRecorder).record(eq(2L),activity.capture());
        String details=com.alibaba.fastjson2.JSON.toJSONString(activity.getValue().details());
        assertTrue(!details.contains("secret") && !details.contains("encrypted"));
    }

    @Test
    void updateReplacesDescriptionAfterTrimming() {
        stubExistingService();
        LlmServiceUpdateReqDTO request = new LlmServiceUpdateReqDTO();
        request.setDescription("  新介绍  ");

        assertTrue(service.updateLlmService(1L, 2L, 3L, request));

        LambdaUpdateWrapper<LlmServiceDO> update = captureUpdateWrapper();
        assertTrue(update.getSqlSet().contains("description"));
        assertTrue(update.getParamNameValuePairs().containsValue("新介绍"));
    }

    @Test
    void updateClearsDescriptionForWhitespaceInput() {
        stubExistingService();
        LlmServiceUpdateReqDTO request = new LlmServiceUpdateReqDTO();
        request.setDescription("   ");

        assertTrue(service.updateLlmService(1L, 2L, 3L, request));

        LambdaUpdateWrapper<LlmServiceDO> update = captureUpdateWrapper();
        assertTrue(update.getSqlSet().contains("description"));
        assertTrue(update.getParamNameValuePairs().containsValue(null));
    }

    @Test
    void credentialUpdateRecordsOnlySafeChangeMarker() {
        stubExistingService();
        when(aesUtil.encrypt("plain-secret")).thenReturn("encrypted-secret");
        LlmServiceUpdateReqDTO request = new LlmServiceUpdateReqDTO();
        request.setApiKey("plain-secret");

        assertTrue(service.updateLlmService(1L, 2L, 3L, request));

        ArgumentCaptor<TenantActivityDraft> captor = ArgumentCaptor.forClass(TenantActivityDraft.class);
        verify(activityRecorder).record(eq(2L), captor.capture());
        TenantActivityDraft draft = captor.getValue();
        assertEquals(TenantActivityType.LLM_SERVICE_UPDATED, draft.type());
        assertEquals(Boolean.TRUE, draft.details().get("credentialChanged"));
        assertTrue(((java.util.List<?>) draft.details().get("changedFields")).contains("credential"));
        String details = com.alibaba.fastjson2.JSON.toJSONString(draft.details());
        assertFalse(details.contains("plain-secret"));
        assertFalse(details.contains("encrypted-secret"));
    }

    @Test
    void submittingSameLimitDoesNotProduceFalseChangedField() {
        LlmServiceDO existing = existingService();
        existing.setRateLimitRpm(60);
        when(llmServiceMapper.selectOne(any())).thenReturn(existing);
        when(llmServiceMapper.update(any(Wrapper.class))).thenReturn(1);
        LlmServiceUpdateReqDTO request = new LlmServiceUpdateReqDTO();
        request.setRpm(60);

        assertTrue(service.updateLlmService(1L, 2L, 3L, request));

        verify(activityRecorder, never()).record(anyLong(), any(TenantActivityDraft.class));
    }

    @Test
    void enableDisableAndRemoveUseExistingNameSnapshot() {
        LlmServiceDO existing = existingService();
        when(llmServiceMapper.selectOne(any())).thenReturn(existing);
        when(llmServiceMapper.update(any(Wrapper.class))).thenReturn(1);

        assertTrue(service.disableLlmService(1L, 2L, 3L));
        ArgumentCaptor<TenantActivityDraft> captor = ArgumentCaptor.forClass(TenantActivityDraft.class);
        verify(activityRecorder).record(eq(2L), captor.capture());
        assertEquals(TenantActivityType.LLM_SERVICE_DISABLED, captor.getValue().type());
        assertEquals("openai.gpt-test", captor.getValue().targetName());

        org.mockito.Mockito.reset(activityRecorder);
        existing.setStatus(0);
        assertTrue(service.enableLlmService(1L, 2L, 3L));
        verify(activityRecorder).record(eq(2L), captor.capture());
        assertEquals(TenantActivityType.LLM_SERVICE_ENABLED, captor.getValue().type());

        org.mockito.Mockito.reset(activityRecorder);
        existing.setStatus(1);
        when(redisTemplate.opsForValue().get(RedisKeyConstant.LLM_REMOVE_TOKEN_KEY + 3L)).thenReturn("remove-token");
        when(jwtUtil.validateToken("remove-token")).thenReturn(true);
        LlmServiceRemoveReqDTO remove = new LlmServiceRemoveReqDTO();
        remove.setToken("remove-token");
        assertTrue(service.removeLlmService(1L, 2L, 3L, remove));
        verify(activityRecorder).record(eq(2L), captor.capture());
        assertEquals(TenantActivityType.LLM_SERVICE_REMOVED, captor.getValue().type());
        assertEquals("openai.gpt-test", captor.getValue().targetName());
    }

    @Test
    void createAndUpdateRejectDescriptionsLongerThanOneHundredCharacters() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        String overlong = "模".repeat(101);

        LlmServiceAddReqDTO addRequest = new LlmServiceAddReqDTO();
        addRequest.setDescription(overlong);
        LlmServiceUpdateReqDTO updateRequest = new LlmServiceUpdateReqDTO();
        updateRequest.setDescription(overlong);

        assertTrue(validator.validate(addRequest).stream()
                .anyMatch(violation -> "description".equals(violation.getPropertyPath().toString())));
        assertTrue(validator.validate(updateRequest).stream()
                .anyMatch(violation -> "description".equals(violation.getPropertyPath().toString())));
    }

    @Test
    void listResponseMapsTheProviderSideModelName() throws NoSuchMethodException {
        Method selectByTenantId = LlmServiceMapper.class.getMethod("selectByTenantId", Long.class);
        String sql = String.join(" ", selectByTenantId.getAnnotation(Select.class).value());
        Results results = selectByTenantId.getAnnotation(Results.class);

        assertTrue(sql.contains("tl.model_name"));
        assertTrue(Arrays.stream(results.value()).anyMatch(result ->
                "modelName".equals(result.property()) && "model_name".equals(result.column())));

        LlmServiceListRespDTO.LlmServiceInfo item = new LlmServiceListRespDTO.LlmServiceInfo()
                .setModelName("gpt-4o-2024-11-20");
        assertEquals("gpt-4o-2024-11-20", item.getModelName());
    }

    private void stubExistingService() {
        when(llmServiceMapper.selectOne(any())).thenReturn(existingService());
        when(llmServiceMapper.update(any(Wrapper.class))).thenReturn(1);
    }

    private LlmServiceDO existingService() {
        return LlmServiceDO.builder()
                .serviceId(3L)
                .tenantId(2L)
                .name("openai.gpt-test")
                .provider("openai")
                .apiUrl("https://example.invalid/v1")
                .apiKey("encrypted")
                .description("旧介绍")
                .status(1)
                .build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private LambdaUpdateWrapper<LlmServiceDO> captureUpdateWrapper() {
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(llmServiceMapper).update(captor.capture());
        return (LambdaUpdateWrapper<LlmServiceDO>) captor.getValue();
    }
}
