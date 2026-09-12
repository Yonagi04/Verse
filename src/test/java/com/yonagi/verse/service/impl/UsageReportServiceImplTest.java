package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.config.UsageReportingProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UsageBreakdownDimension;
import com.yonagi.verse.common.enums.UsageBreakdownOrder;
import com.yonagi.verse.common.enums.UsageGranularity;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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
        service=new UsageReportServiceImpl(aggregateMapper,userTenantService,new UsageReportingProperties(),
                mock(ApiKeyMapper.class),mock(LlmServiceMapper.class),mock(UserMapper.class));
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
        service=new UsageReportServiceImpl(aggregateMapper,userTenantService,new UsageReportingProperties(),apiKeyMapper,
                mock(LlmServiceMapper.class),mock(UserMapper.class));
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
        service=new UsageReportServiceImpl(aggregateMapper,userTenantService,new UsageReportingProperties(),apiKeyMapper,llmServiceMapper,mock(UserMapper.class));
        when(userTenantService.getRoleByUserIdAndTenantId(10L,20L)).thenReturn("ADMIN");
        ApiKeyDO key=new ApiKeyDO();key.setApiKeyId(30L);key.setTenantId(20L);key.setUserId(11L);when(apiKeyMapper.selectOne(any())).thenReturn(key);
        when(llmServiceMapper.exists(any())).thenReturn(true);when(aggregateMapper.summarizeHours(20L,11L,30L,40L,from,to)).thenReturn(List.of());
        service.query(new UserContext().setUserId(10L),20L,UsageGranularity.DAY,from,to,11L,30L,40L);
        verify(aggregateMapper).summarizeHours(20L,11L,30L,40L,from,to);
    }

    @Test
    void adminBreakdownUsesDeterministicTieOrderAndZeroRatios() {
        when(userTenantService.getRoleByUserIdAndTenantId(10L,20L)).thenReturn("ADMIN");
        UsageBreakdownRow later=row(2L,"z-model",0,0,"0.000000000000000001");
        UsageBreakdownRow first=row(1L,"a-model",0,0,"0.000000000000000001");
        when(aggregateMapper.summarizeBreakdown("MODEL",20L,null,null,null,from,to)).thenReturn(new java.util.ArrayList<>(List.of(later,first)));
        UsageBreakdownRespDTO result=service.breakdown(new UserContext().setUserId(10L),20L,UsageGranularity.DAY,
                from,to,null,null,null,UsageBreakdownDimension.MODEL,UsageBreakdownOrder.TOTAL_TOKENS,10);
        assertEquals(List.of("1:a-model","2:z-model"),result.getItems().stream().map(UsageBreakdownRespDTO.Item::getId).toList());
        assertTrue(result.getItems().stream().allMatch(item->"0".equals(item.getRatio())));
        assertEquals("0.000000000000000002",result.getTotal().getEstimatedCostFen());
    }

    @Test
    void adminCanRankMembersWithSafeLabels() {
        UserMapper userMapper=mock(UserMapper.class);
        service=new UsageReportServiceImpl(aggregateMapper,userTenantService,new UsageReportingProperties(),mock(ApiKeyMapper.class),mock(LlmServiceMapper.class),userMapper);
        when(userTenantService.getRoleByUserIdAndTenantId(10L,20L)).thenReturn("ADMIN");
        when(aggregateMapper.summarizeBreakdown("MEMBER",20L,null,null,null,from,to)).thenReturn(new java.util.ArrayList<>(List.of(row(11L,null,5,1,"1"))));
        UserDO user=new UserDO();user.setUserId(11L);user.setUsername("alice");user.setNickname("Alice");when(userMapper.selectList(any())).thenReturn(List.of(user));
        var result=service.breakdown(new UserContext().setUserId(10L),20L,UsageGranularity.DAY,from,to,null,null,null,
                UsageBreakdownDimension.MEMBER,UsageBreakdownOrder.REQUEST_COUNT,10);
        assertEquals("Alice (alice)",result.getItems().get(0).getLabel());assertEquals("1",result.getItems().get(0).getRatio());
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

    private UsageBreakdownRow row(Long id,String model,long tokens,long requests,String cost) {
        UsageBreakdownRow row=new UsageBreakdownRow();row.setDimensionId(id);row.setModel(model);row.setInputTokens(tokens);
        row.setOutputTokens(0L);row.setTotalTokens(tokens);row.setRequestCount(requests);row.setEstimatedCostFen(new BigDecimal(cost));
        row.setExactUsageCount(0L);row.setEstimatedUsageCount(0L);row.setUnknownUsageCount(0L);row.setCalculatedCount(0L);
        row.setUnpricedCount(0L);row.setUncalculableCount(0L);row.setNotChargeableCount(0L);return row;
    }
}
