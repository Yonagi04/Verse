package com.yonagi.verse.controller;

import com.yonagi.verse.common.config.UsageReportingProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UsageBreakdownDimension;
import com.yonagi.verse.common.enums.UsageBreakdownOrder;
import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.enums.UsageReportingErrorCodeEnum;
import com.yonagi.verse.common.security.CurrentUserArgumentResolver;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.common.web.GlobalExceptionHandler;
import com.yonagi.verse.dao.mapper.TokenUsageMapper;
import com.yonagi.verse.dao.projection.UsageRawExportRow;
import com.yonagi.verse.dto.resp.UsageBreakdownRespDTO;
import com.yonagi.verse.service.UsageExportService;
import com.yonagi.verse.service.UsageReportService;
import com.yonagi.verse.service.impl.UsageExportServiceImpl;
import com.yonagi.verse.service.reporting.UsageReportFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UsageReportControllerTest {
    @Test
    void rawExportOverflowAfterFullBatchReturnsJsonWithoutAnAttachment() throws Exception {
        UsageReportService reports = mock(UsageReportService.class);
        TokenUsageMapper mapper = mock(TokenUsageMapper.class);
        UsageReportingProperties properties = new UsageReportingProperties();
        properties.setExportBatchSize(1);
        properties.setExportMaxRows(1);
        UsageExportService export = new UsageExportServiceImpl(reports, mapper, properties, new SimpleMeterRegistry());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UsageReportController(reports, export))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        LocalDateTime from = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime to = from.plusDays(1);
        when(reports.resolveFilter(any(), eq(20L), eq(UsageGranularity.DAY), isNull(), isNull(), isNull(),
                isNull(), isNull()))
                .thenReturn(new UsageReportFilter(20L, 10L, null, null, from, to, UsageGranularity.DAY, false));
        when(mapper.countRawExport(20L, 10L, null, null, from, to)).thenReturn(1L);
        UsageRawExportRow first = new UsageRawExportRow();
        first.setId(1L);
        first.setRequestStartedAt(from);
        UsageRawExportRow extra = new UsageRawExportRow();
        extra.setId(2L);
        extra.setRequestStartedAt(from);
        when(mapper.selectRawExportBatch(eq(20L), eq(10L), isNull(), isNull(), eq(from), eq(to),
                any(), any(), eq(1))).thenReturn(List.of(first), List.of(extra));

        UserContextHolder.set(new UserContext().setUserId(10L).setCurrentTenantId(20L));
        try {
            mvc.perform(get("/api/v1/usage/20/export").param("type", "raw"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
                    .andExpect(jsonPath("$.code").value(UsageReportingErrorCodeEnum.EXPORT_TOO_LARGE.code()));
        } finally {
            UserContextHolder.clear();
        }
    }

    @Test
    void breakdownParsesPublicParametersAndRejectsUnknownDimension() {
        UsageReportService service=mock(UsageReportService.class);
        UsageReportController controller=new UsageReportController(service,mock(UsageExportService.class));
        when(service.breakdown(any(),eq(20L),eq(UsageGranularity.DAY),isNull(),isNull(),isNull(),isNull(),isNull(),
                eq(UsageBreakdownDimension.API_KEY),eq(UsageBreakdownOrder.ESTIMATED_COST_FEN),eq(20))).thenReturn(new UsageBreakdownRespDTO());
        assertNotNull(controller.breakdown(new UserContext().setUserId(10L),20L,"day",null,null,null,null,null,"api_key","estimatedCostFen",20));
        verify(service).breakdown(any(),eq(20L),eq(UsageGranularity.DAY),isNull(),isNull(),isNull(),isNull(),isNull(),
                eq(UsageBreakdownDimension.API_KEY),eq(UsageBreakdownOrder.ESTIMATED_COST_FEN),eq(20));
        assertEquals("A000954",assertThrows(ClientException.class,()->controller.breakdown(new UserContext().setUserId(10L),20L,
                "day",null,null,null,null,null,"unknown","totalTokens",10)).getErrorCode());
    }
}
