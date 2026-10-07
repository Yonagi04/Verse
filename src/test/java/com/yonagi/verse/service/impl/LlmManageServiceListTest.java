package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.LlmServiceCapabilityDO;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.resp.LlmServiceListRespDTO.LlmServiceInfo;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.forward.CapabilityConfiguration;
import com.yonagi.verse.service.pricing.LlmMetadataService;
import com.yonagi.verse.service.pricing.PricingConfigurationService;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmManageServiceListTest {
    private final LlmServiceMapper mapper = mock(LlmServiceMapper.class);
    private final LlmServiceCapabilityMapper capabilities = mock(LlmServiceCapabilityMapper.class);
    private final LlmMetadataService metadata = mock(LlmMetadataService.class);
    private final TenantAccessPolicy access = mock(TenantAccessPolicy.class);
    private LlmManageServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "llm-list"),
                LlmServiceCapabilityDO.class);
        service = new LlmManageServiceImpl(access, mock(TenantMapper.class), mock(UserTenantService.class),
                mock(AesUtil.class), mock(StringRedisTemplate.class), mock(UserMapper.class), mock(JwtUtil.class),
                mock(RedissonClient.class), metadata, mock(PricingConfigurationService.class),
                mock(TenantActivityRecorder.class), capabilities);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    void batchesOnlyCurrentPageAndPreservesLegacyDefaultCapabilities() {
        var page = new Page<LlmServiceInfo>(2, 2, 5);
        page.setRecords(List.of(new LlmServiceInfo().setServiceId(31L), new LlmServiceInfo().setServiceId(32L)));
        when(mapper.selectPageByTenantId(any(), eq(2L), eq("谷歌"), eq(List.of("gemini")), eq(List.of("chat", "vision"))))
                .thenAnswer(call -> {
                    Page<?> requested = call.getArgument(0);
                    assertEquals(2, requested.getCurrent());
                    assertEquals(2, requested.getSize());
                    return page;
                });
        when(metadata.tagsByServiceIds(List.of(31L, 32L))).thenReturn(Map.of(31L, List.of("chat")));
        var row = new LlmServiceCapabilityDO();
        row.setServiceId(31L); row.setOperation("CHAT"); row.setUpstreamProtocol("OPENAI_CHAT"); row.setEnabled(0);
        when(capabilities.selectList(any())).thenReturn(List.of(row));

        var result = service.listLlmService(1L, 2L, 2, 2, " 谷歌 ", "chat, vision,chat");

        assertEquals(5L, result.getTotal());
        assertEquals(3L, result.getTotalPages());
        assertEquals(List.of("chat"), result.getServiceInfoList().getFirst().getTagCodes());
        assertFalse(result.getServiceInfoList().getFirst().getCapabilities().getFirst().getEnabled());
        assertEquals(CapabilityConfiguration.normalize(null), result.getServiceInfoList().getLast().getCapabilities());
        verify(metadata).validateCodes(List.of("chat", "vision"));
        verify(metadata).tagsByServiceIds(List.of(31L, 32L));
        verify(capabilities, times(1)).selectList(argThat(wrapper -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) wrapper;
            query.getSqlSegment();
            return query.getParamNameValuePairs().values().containsAll(List.of(31L, 32L))
                    && query.getParamNameValuePairs().size() == 2;
        }));
    }

    @Test
    void outOfRangePageDoesNotReadTagsOrCapabilities() {
        var empty = new Page<LlmServiceInfo>(Integer.MAX_VALUE, 100, 3);
        when(mapper.selectPageByTenantId(any(), eq(2L), isNull(), eq(List.of()), isNull())).thenReturn(empty);
        var result = service.listLlmService(1L, 2L, Integer.MAX_VALUE, 100, null, null);
        assertTrue(result.getServiceInfoList().isEmpty());
        assertEquals(3L, result.getTotal());
        verify(metadata, never()).tagsByServiceIds(any());
        verifyNoInteractions(capabilities);
    }

    @ParameterizedTest
    @CsvSource({"0,10", "1,0", "1,-1", "1,101"})
    void rejectsInvalidPaginationBeforeDataAccess(int page, int size) {
        assertThrows(ClientException.class, () -> service.listLlmService(1L, 2L, page, size, null, null));
        verifyNoInteractions(mapper, capabilities, metadata, access);
    }

    @Test
    void invalidTagAndAuthorizationFailurePropagate() {
        doThrow(new ClientException("标签无效")).when(metadata).validateCodes(List.of("bad"));
        assertThrows(ClientException.class, () -> service.listLlmService(1L, 2L, 1, 10, null, "bad"));
        verifyNoInteractions(mapper, capabilities);
        doThrow(new ClientException("无权访问")).when(access).requireMember(1L, 3L);
        assertThrows(ClientException.class, () -> service.listLlmService(1L, 3L, 1, 10, null, null));
        verifyNoInteractions(mapper, capabilities);
    }

    @Test
    void countUsesAggregateWithoutReadingCapabilities() {
        when(mapper.countByTenantId(2L)).thenReturn(500L);
        assertEquals(500, service.getLlmServiceCount(1L, 2L));
        verifyNoInteractions(capabilities, metadata);
    }
}
