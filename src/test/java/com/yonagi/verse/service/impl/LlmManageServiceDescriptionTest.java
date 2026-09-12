package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.LlmServiceAddReqDTO;
import com.yonagi.verse.dto.req.LlmServiceUpdateReqDTO;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmManageServiceDescriptionTest {

    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    private final UserTenantService userTenantService = mock(UserTenantService.class);
    private final AesUtil aesUtil = mock(AesUtil.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    private final LlmServiceMapper llmServiceMapper = mock(LlmServiceMapper.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final LlmMetadataService metadataService = mock(LlmMetadataService.class);
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
                mock(JwtUtil.class),
                redissonClient,
                metadataService,
                mock(PricingConfigurationService.class)
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
        LlmServiceDO existing = LlmServiceDO.builder()
                .serviceId(3L)
                .tenantId(2L)
                .name("openai.gpt-test")
                .description("旧介绍")
                .status(1)
                .build();
        when(llmServiceMapper.selectOne(any())).thenReturn(existing);
        when(llmServiceMapper.update(any(Wrapper.class))).thenReturn(1);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private LambdaUpdateWrapper<LlmServiceDO> captureUpdateWrapper() {
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(llmServiceMapper).update(captor.capture());
        return (LambdaUpdateWrapper<LlmServiceDO>) captor.getValue();
    }
}
