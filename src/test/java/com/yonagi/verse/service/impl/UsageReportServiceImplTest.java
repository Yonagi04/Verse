package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.service.reporting.UsageReportAccessPolicy;

import com.yonagi.verse.common.config.UsageReportingProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UsageBreakdownDimension;
import com.yonagi.verse.common.enums.UsageBreakdownOrder;
import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.enums.UsageReportingErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.mapper.ApiKeyMapper;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.TokenUsageHourlyAggMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.projection.UsageBreakdownRow;
import com.yonagi.verse.dto.resp.UsageBreakdownRespDTO;
import com.yonagi.verse.service.UserTenantService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UsageReportServiceImplTest {
    private TokenUsageHourlyAggMapper aggregateMapper;
    private UserTenantService userTenantService;
    private UsageReportServiceImpl service;
    private final LocalDateTime from=LocalDateTime.of(2026,9,1,0,0);
    private final LocalDateTime to=LocalDateTime.of(2026,9,2,0,0);

    @BeforeEach
    void setUp() {
        aggregateMapper=mock(TokenUsageHourlyAggMapper.class);
        userTenantService=mock(UserTenantService.class);
        service=new UsageReportServiceImpl(aggregateMapper,
                userTenantService,
                new UsageReportingProperties(),
                mock(ApiKeyMapper.class),
                mock(LlmServiceMapper.class),
                mock(UserMapper.class),
                new UsageReportAccessPolicy(userTenantService, new UsageReportingProperties(), mock(ApiKeyMapper.class), mock(LlmServiceMapper.class)));
        when(userTenantService.isUserJoinedTenant(anyLong(),anyLong())).thenReturn(true);
    }

    @Test
    void memberCannotRequestAnotherMembersUsage() {
        when(userTenantService.getRoleByUserIdAndTenantId(10L,20L)).thenReturn("MEMBER");
        UserContext context=new UserContext().setUserId(10L);
        ClientException error=assertThrows(ClientException.class,()->service.query(context,20L,UsageGranularity.DAY,from,to,11L,null,null));
        assertEquals("A000952",error.getErrorCode());
        verifyNoInteractions(aggregateMapper);
    }

    @Test
    void memberScopeAndCombinedFiltersReachAggregateQuery() {
        when(userTenantService.getRoleByUserIdAndTenantId(10L,20L)).thenReturn("MEMBER");
        when(aggregateMapper.summarizeHours(20L,10L,null,null,from,to)).thenReturn(List.of());
        service.query(new UserContext().setUserId(10L),20L,UsageGranularity.DAY,from,to,null,null,null);
        verify(aggregateMapper).summarizeHours(20L,10L,null,null,from,to);
    }

    @Test
    void memberCannotRequestMemberDimension() {
        when(userTenantService.getRoleByUserIdAndTenantId(10L,20L)).thenReturn("MEMBER");
        ClientException error=assertThrows(ClientException.class,()->service.breakdown(new UserContext().setUserId(10L),20L,
                UsageGranularity.DAY,from,to,null,null,null,UsageBreakdownDimension.MEMBER,UsageBreakdownOrder.TOTAL_TOKENS,10));
        assertEquals("A000952",error.getErrorCode());verifyNoInteractions(aggregateMapper);
    }

    @Test
    void memberCannotUseAnotherUsersApiKey() {
        ApiKeyMapper apiKeyMapper=mock(ApiKeyMapper.class);
        service=new UsageReportServiceImpl(aggregateMapper,
                userTenantService,
                new UsageReportingProperties(),
                apiKeyMapper,
                mock(LlmServiceMapper.class),
                mock(UserMapper.class),
                new UsageReportAccessPolicy(userTenantService, new UsageReportingProperties(), apiKeyMapper, mock(LlmServiceMapper.class)));
        when(userTenantService.getRoleByUserIdAndTenantId(10L,20L)).thenReturn("MEMBER");
        ApiKeyDO key=new ApiKeyDO();key.setApiKeyId(30L);key.setTenantId(20L);key.setUserId(11L);
        when(apiKeyMapper.selectOne(any())).thenReturn(key);
        ClientException error=assertThrows(ClientException.class,()->service.query(new UserContext().setUserId(10L),20L,
                UsageGranularity.DAY,from,to,null,30L,null));
        assertEquals("A000953",error.getErrorCode());verifyNoInteractions(aggregateMapper);
    }

    @Test
    void adminCombinedFiltersAreAppliedTogether() {
        ApiKeyMapper apiKeyMapper=mock(ApiKeyMapper.class);LlmServiceMapper llmServiceMapper=mock(LlmServiceMapper.class);
        service=new UsageReportServiceImpl(aggregateMapper,
                userTenantService,
                new UsageReportingProperties(),
                apiKeyMapper,
                llmServiceMapper,
                mock(UserMapper.class),
                new UsageReportAccessPolicy(userTenantService, new UsageReportingProperties(), apiKeyMapper, llmServiceMapper));
        when(userTenantService.getRoleByUserIdAndTenantId(10L,20L)).thenReturn("ADMIN");
        ApiKeyDO key=new ApiKeyDO();key.setApiKeyId(30L);key.setTenantId(20L);key.setUserId(11L);when(apiKeyMapper.selectOne(any())).thenReturn(key);
        when(llmServiceMapper.exists(any())).thenReturn(true);when(aggregateMapper.summarizeHours(20L,11L,30L,40L,from,to)).thenReturn(List.of());
        service.query(new UserContext().setUserId(10L),20L,UsageGranularity.DAY,from,to,11L,30L,40L);
        verify(aggregateMapper).summarizeHours(20L,11L,30L,40L,from,to);
        service.breakdown(new UserContext().setUserId(10L), 20L, UsageGranularity.DAY, from, to, 11L, 30L, 40L,
                UsageBreakdownDimension.MODEL, UsageBreakdownOrder.TOTAL_TOKENS, 100);
        verify(aggregateMapper).summarizeRankedBreakdown(
                UsageBreakdownDimension.MODEL, UsageBreakdownOrder.TOTAL_TOKENS, 20L, 11L, 30L, 40L, from, to, 101);
    }

    @Test
    void breakdownKeepsDatabaseOrderAndZeroRatios() {
        when(userTenantService.getRoleByUserIdAndTenantId(10L, 20L)).thenReturn("ADMIN");
        UsageBreakdownRow total = row(null, null, 0, 0, "0.000000000000000002");
        UsageBreakdownRow first = row(1L, "a-model", 0, 0, "0.000000000000000001");
        UsageBreakdownRow second = row(2L, "z-model", 0, 0, "0.000000000000000001");
        when(aggregateMapper.summarizeRankedBreakdown(UsageBreakdownDimension.MODEL, UsageBreakdownOrder.TOTAL_TOKENS,
                20L, null, null, null, from, to, 11)).thenReturn(List.of(total, first, second));
        UsageBreakdownRespDTO result = service.breakdown(new UserContext().setUserId(10L), 20L, UsageGranularity.DAY,
                from, to, null, null, null, UsageBreakdownDimension.MODEL, UsageBreakdownOrder.TOTAL_TOKENS, 10);
        assertEquals(List.of("1:a-model", "2:z-model"),
                result.getItems().stream().map(UsageBreakdownRespDTO.Item::getId).toList());
        assertTrue(result.getItems().stream().allMatch(item -> "0".equals(item.getRatio())));
        assertEquals("0.000000000000000002", result.getTotal().getEstimatedCostFen());
    }

    @Test
    void adminCanRankMembersWithSafeLabels() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "report-members"),
                UserDO.class);
        UserMapper userMapper=mock(UserMapper.class);
        service=new UsageReportServiceImpl(aggregateMapper,
                userTenantService,
                new UsageReportingProperties(),
                mock(ApiKeyMapper.class),
                mock(LlmServiceMapper.class),
                userMapper,
                new UsageReportAccessPolicy(userTenantService, new UsageReportingProperties(), mock(ApiKeyMapper.class), mock(LlmServiceMapper.class)));
        when(userTenantService.getRoleByUserIdAndTenantId(10L,20L)).thenReturn("ADMIN");
        when(aggregateMapper.summarizeRankedBreakdown(UsageBreakdownDimension.MEMBER, UsageBreakdownOrder.REQUEST_COUNT,
                20L, null, null, null, from, to, 2))
                .thenReturn(List.of(row(null, null, 6, 1, "3"), row(11L, null, 5, 1, "1")));
        UserDO user = new UserDO();
        user.setUserId(11L);
        user.setUsername("alice");
        user.setNickname("Alice");
        when(userMapper.selectList(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            query.getSqlSegment();
            assertEquals(List.of(11L), List.copyOf(query.getParamNameValuePairs().values()));
            return List.of(user);
        });
        UsageBreakdownRespDTO result = service.breakdown(new UserContext().setUserId(10L), 20L, UsageGranularity.DAY,
                from, to, null, null, null, UsageBreakdownDimension.MEMBER, UsageBreakdownOrder.REQUEST_COUNT, 1);
        assertEquals(1, result.getItems().size());
        assertEquals("Alice (alice)", result.getItems().getFirst().getLabel());
        assertEquals("1", result.getItems().getFirst().getRatio());
        assertEquals("6", result.getTotal().getTotalTokens());
    }

    @Test
    void apiKeyBreakdownUsesFullCostTotalAndOnlyLoadsRankedTenantLabels() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "report-api-keys"),
                ApiKeyDO.class);
        ApiKeyMapper keys = mock(ApiKeyMapper.class);
        LlmServiceMapper models = mock(LlmServiceMapper.class);
        service = new UsageReportServiceImpl(aggregateMapper, userTenantService, new UsageReportingProperties(),
                keys, models, mock(UserMapper.class),
                new UsageReportAccessPolicy(userTenantService, new UsageReportingProperties(), keys, models));
        when(userTenantService.getRoleByUserIdAndTenantId(10L, 20L)).thenReturn("MEMBER");
        when(aggregateMapper.summarizeRankedBreakdown(UsageBreakdownDimension.API_KEY,
                UsageBreakdownOrder.ESTIMATED_COST_FEN, 20L, 10L, null, null, from, to, 3))
                .thenReturn(List.of(row(null, null, 5, 3, "0.000000000000000017"),
                        row(31L, null, 3, 1, "0.000000000000000011"),
                        row(32L, null, 1, 1, "0.000000000000000003")));
        ApiKeyDO key = new ApiKeyDO();
        key.setApiKeyId(31L);
        key.setName("报表");
        key.setKeyPrefix("prefix");
        when(keys.selectList(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            assertTrue(query.getSqlSegment().contains("tenant_id"));
            assertEquals(Set.of(20L, 31L, 32L), Set.copyOf(query.getParamNameValuePairs().values()));
            return List.of(key);
        });

        UsageBreakdownRespDTO result = service.breakdown(new UserContext().setUserId(10L), 20L, UsageGranularity.DAY,
                from, to, null, null, null, UsageBreakdownDimension.API_KEY, UsageBreakdownOrder.ESTIMATED_COST_FEN, 2);

        assertEquals(List.of("报表 (prefix)", "API Key 32"),
                result.getItems().stream().map(UsageBreakdownRespDTO.Item::getLabel).toList());
        assertEquals(List.of("0.64705882", "0.17647059"),
                result.getItems().stream().map(UsageBreakdownRespDTO.Item::getRatio).toList());
        assertEquals("0.000000000000000017", result.getTotal().getEstimatedCostFen());
    }

    @Test
    void breakdownKeepsIntegerTotalsWiderThanOneBigint() {
        when(userTenantService.getRoleByUserIdAndTenantId(10L, 20L)).thenReturn("ADMIN");
        UsageBreakdownRow total = row(null, null, 0, 0, "0");
        total.setTotalTokens(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO));
        when(aggregateMapper.summarizeRankedBreakdown(UsageBreakdownDimension.MODEL, UsageBreakdownOrder.TOTAL_TOKENS,
                20L, null, null, null, from, to, 2))
                .thenReturn(List.of(total, row(1L, "model", Long.MAX_VALUE, 1, "0")));

        UsageBreakdownRespDTO result = service.breakdown(new UserContext().setUserId(10L), 20L, UsageGranularity.DAY,
                from, to, null, null, null, UsageBreakdownDimension.MODEL, UsageBreakdownOrder.TOTAL_TOKENS, 1);

        assertEquals("18446744073709551614", result.getTotal().getTotalTokens());
        assertEquals("9223372036854775807", result.getItems().getFirst().getMetrics().getTotalTokens());
        assertEquals("0.5", result.getItems().getFirst().getRatio());
    }

    @Test
    void emptyBreakdownReturnsZeroTotalAndNoRankedItems() {
        when(userTenantService.getRoleByUserIdAndTenantId(10L, 20L)).thenReturn("ADMIN");
        when(aggregateMapper.summarizeRankedBreakdown(UsageBreakdownDimension.MEMBER, UsageBreakdownOrder.REQUEST_COUNT,
                20L, null, null, null, from, to, 2)).thenReturn(List.of());

        UsageBreakdownRespDTO result = service.breakdown(new UserContext().setUserId(10L), 20L, UsageGranularity.DAY,
                from, to, null, null, null, UsageBreakdownDimension.MEMBER, UsageBreakdownOrder.REQUEST_COUNT, 1);

        assertTrue(result.getItems().isEmpty());
        assertEquals("0", result.getTotal().getTotalTokens());
        assertEquals("0", result.getTotal().getEstimatedCostFen());
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, 0, 101, Integer.MAX_VALUE})
    void breakdownRejectsInvalidLimitBeforeReadingTheProjection(int limit) {
        when(userTenantService.getRoleByUserIdAndTenantId(10L, 20L)).thenReturn("ADMIN");
        UserContext context = new UserContext().setUserId(10L);

        ClientException failure = assertThrows(ClientException.class, () -> service.breakdown(
                context, 20L, UsageGranularity.DAY, from, to, null, null, null,
                UsageBreakdownDimension.MODEL, UsageBreakdownOrder.TOTAL_TOKENS, limit));

        assertEquals(UsageReportingErrorCodeEnum.FILTER_INVALID.code(), failure.getErrorCode());
        verifyNoInteractions(aggregateMapper);
    }

    @Test
    void emptyRangeIsZeroFilledWithPrecisionSafeStrings() {
        when(userTenantService.getRoleByUserIdAndTenantId(10L,20L)).thenReturn("ADMIN");
        when(aggregateMapper.summarizeHours(anyLong(),any(),any(),any(),any(),any())).thenReturn(List.of());
        var result=service.query(new UserContext().setUserId(10L),20L,UsageGranularity.HOUR,from,from.plusHours(2),null,null,null);
        assertEquals(2,result.getPoints().size());assertEquals("0",result.getTotal().getTotalTokens());
        assertEquals("0",result.getPoints().get(0).getEstimatedCostFen());
    }

    @Test
    void invalidOrOversizedRangesAreRejected() {
        when(userTenantService.getRoleByUserIdAndTenantId(10L,20L)).thenReturn("ADMIN");
        UserContext context=new UserContext().setUserId(10L);
        assertEquals("A000951",assertThrows(ClientException.class,()->service.query(context,20L,UsageGranularity.DAY,to,from,null,null,null)).getErrorCode());
        assertEquals("A000951",assertThrows(ClientException.class,()->service.query(context,20L,UsageGranularity.DAY,from,from.plusDays(367),null,null,null)).getErrorCode());
    }

    private UsageBreakdownRow row(Long id, String model, long tokens, long requests, String cost) {
        UsageBreakdownRow row = new UsageBreakdownRow();
        row.setDimensionId(id);
        row.setModel(model);
        row.setInputTokens(BigInteger.valueOf(tokens));
        row.setTotalTokens(BigInteger.valueOf(tokens));
        row.setRequestCount(BigInteger.valueOf(requests));
        row.setEstimatedCostFen(new BigDecimal(cost));
        return row;
    }
}
