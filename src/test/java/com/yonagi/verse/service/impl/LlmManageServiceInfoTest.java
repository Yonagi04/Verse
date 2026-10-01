package com.yonagi.verse.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.LlmServiceCapabilityMapper;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.pricing.LlmMetadataService;
import com.yonagi.verse.service.pricing.PricingConfigurationService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmManageServiceInfoTest {

    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    private final UserTenantService userTenantService = mock(UserTenantService.class);
    private final AesUtil aesUtil = mock(AesUtil.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    private final LlmServiceMapper llmServiceMapper = mock(LlmServiceMapper.class);
    private LlmManageServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "llm-info-test"),
                LlmServiceDO.class
        );
        service = new LlmManageServiceImpl(
                tenantMapper, userTenantService, aesUtil, redisTemplate, mock(UserMapper.class),
                mock(JwtUtil.class), mock(RedissonClient.class), mock(LlmMetadataService.class),
                mock(PricingConfigurationService.class), mock(TenantActivityRecorder.class),
                mock(LlmServiceCapabilityMapper.class)
        );
        ReflectionTestUtils.setField(service, "baseMapper", llmServiceMapper);
        when(tenantMapper.selectOne(any())).thenReturn(new TenantDO());
        when(userTenantService.isUserJoinedTenant(1L, 2L)).thenReturn(true);
        when(aesUtil.decrypt("encrypted")).thenReturn("sk-test-secret");
    }

    @ParameterizedTest
    @MethodSource("settingsCases")
    void detailParsesProviderSettingsFromDatabaseAndCache(String settings, Map<String, String> expected,
                                                         boolean cached) {
        LlmServiceDO model = LlmServiceDO.builder()
                .serviceId(3L).tenantId(2L).createdBy(1L).name("test-model")
                .provider("openai").modelName("gpt-test").description("测试模型")
                .apiKey("encrypted").providerSettings(settings).contextWindow(8192L)
                .maxOutputTokens(2048L).status(1).build();
        model.setDelFlag(0);
        String cacheKey = RedisKeyConstant.LLM_SERVICE_INFO_KEY + 3L;
        when(redisTemplate.opsForValue().get(cacheKey)).thenReturn(cached ? JSON.toJSONString(model) : null);
        when(llmServiceMapper.selectOne(any())).thenReturn(model);

        var response = service.getLlmInfo(1L, 2L, 3L);

        assertEquals(expected, response.getProviderSettings());
        assertEquals(3L, response.getServiceId());
        assertEquals("test-model", response.getName());
        assertEquals("gpt-test", response.getModelName());
        assertEquals("测试模型", response.getDescription());
        assertEquals(8192L, response.getContextWindow());
        assertEquals(2048L, response.getMaxOutputTokens());
        assertEquals("sk-te*****", response.getApiKey());
        if (cached) {
            verify(llmServiceMapper, never()).selectOne(any());
            verify(redisTemplate.opsForValue(), never()).set(any(), any(), anyLong(), any(TimeUnit.class));
        } else {
            verify(llmServiceMapper).selectOne(any());
            // 回写缓存仍保存实体 JSON，避免把供应商配置改成 DTO 的 Map 格式。
            verify(redisTemplate.opsForValue()).set(cacheKey, JSON.toJSONString(model), 30, TimeUnit.MINUTES);
        }
    }

    private static Stream<Arguments> settingsCases() {
        Map<String, String> playground = Map.of("playground",
                "{\"system\":true,\"temperature\":{\"min\":0,\"max\":2},\"maxTokens\":{\"min\":1,\"max\":2048}}");
        return Stream.of(false, true).flatMap(cached -> Stream.of(
                Arguments.of(JSON.toJSONString(playground), playground, cached),
                Arguments.of("{\"region\":\"us-east-1\"}", Map.of("region", "us-east-1"), cached),
                Arguments.of("{}", Map.of(), cached),
                Arguments.of("   ", Map.of(), cached),
                Arguments.of(null, Map.of(), cached)
        ));
    }
}
