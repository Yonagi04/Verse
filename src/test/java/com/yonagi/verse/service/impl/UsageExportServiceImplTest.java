package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.config.UsageReportingProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.mapper.TokenUsageMapper;
import com.yonagi.verse.dto.resp.UsageBreakdownRespDTO;
import com.yonagi.verse.dto.resp.UsageReportRespDTO;
import com.yonagi.verse.service.UsageReportService;
import com.yonagi.verse.service.reporting.UsageReportFilter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UsageExportServiceImplTest {
    @Test
    void breakdownWorkbookNeutralizesFormulaTextAndKeepsPrecisionStrings() throws Exception {
        UsageReportService reportService=mock(UsageReportService.class);TokenUsageMapper mapper=mock(TokenUsageMapper.class);
        UsageReportingProperties properties=new UsageReportingProperties();UsageExportServiceImpl service=new UsageExportServiceImpl(reportService,mapper,properties,new SimpleMeterRegistry());
        LocalDateTime from=LocalDateTime.of(2026,9,1,0,0),to=from.plusDays(1);
        UsageReportFilter filter=new UsageReportFilter(20L,null,null,null,from,to,UsageGranularity.DAY,true);
        when(reportService.resolveFilter(any(),eq(20L),any(),eq(from),eq(to),isNull(),isNull(),isNull())).thenReturn(filter);
        UsageReportRespDTO.UsageMetrics metrics=new UsageReportRespDTO.UsageMetrics();metrics.setInputTokens("9007199254740993");
        metrics.setOutputTokens("1");metrics.setTotalTokens("9007199254740994");metrics.setRequestCount("1");metrics.setEstimatedCostFen("0.000000000000000001");
        metrics.setUncalculableCount("0");metrics.setUnpricedCount("0");
        UsageBreakdownRespDTO.Item item=new UsageBreakdownRespDTO.Item();item.setId("1");item.setLabel("=2+2");item.setRatio("1");item.setMetrics(metrics);
        UsageBreakdownRespDTO report=new UsageBreakdownRespDTO();report.setItems(List.of(item));
        when(reportService.breakdown(any(),eq(20L),any(),eq(from),eq(to),isNull(),isNull(),isNull(),any(),any(),eq(10))).thenReturn(report);
        MockHttpServletResponse response=new MockHttpServletResponse();
        service.export(response,new UserContext().setUserId(10L),20L,UsageExportType.BREAKDOWN,UsageGranularity.DAY,from,to,
                null,null,null,UsageBreakdownDimension.MODEL,UsageBreakdownOrder.TOTAL_TOKENS,10);
        assertTrue(response.getContentType().contains("spreadsheetml"));
        try(XSSFWorkbook workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var row=workbook.getSheetAt(0).getRow(1);
            assertEquals("'=2+2",row.getCell(1).getStringCellValue());
            assertEquals("9007199254740994",row.getCell(5).getStringCellValue());
        }
    }

    @Test
    void rawExportRejectsRangeAndRowLimitBeforeWritingBytes() {
        UsageReportService reportService=mock(UsageReportService.class);TokenUsageMapper mapper=mock(TokenUsageMapper.class);
        UsageReportingProperties properties=new UsageReportingProperties();properties.setExportMaxRows(2);
        UsageExportServiceImpl service=new UsageExportServiceImpl(reportService,mapper,properties,new SimpleMeterRegistry());
        LocalDateTime from=LocalDateTime.of(2026,1,1,0,0),longTo=from.plusDays(32);
        when(reportService.resolveFilter(any(),anyLong(),any(),any(),any(),any(),any(),any()))
                .thenReturn(new UsageReportFilter(20L,null,null,null,from,longTo,UsageGranularity.DAY,true));
        MockHttpServletResponse response=new MockHttpServletResponse();
        ClientException rangeError=assertThrows(ClientException.class,()->service.export(response,new UserContext().setUserId(10L),20L,
                UsageExportType.RAW,UsageGranularity.DAY,from,longTo,null,null,null,UsageBreakdownDimension.MODEL,UsageBreakdownOrder.TOTAL_TOKENS,10));
        assertEquals("A000955",rangeError.getErrorCode());assertEquals(0,response.getContentAsByteArray().length);

        LocalDateTime to=from.plusDays(1);UsageReportFilter shortFilter=new UsageReportFilter(20L,null,null,null,from,to,UsageGranularity.DAY,true);
        when(reportService.resolveFilter(any(),anyLong(),any(),any(),any(),any(),any(),any())).thenReturn(shortFilter);
        when(mapper.countRawExport(20L,null,null,null,from,to)).thenReturn(3L);
        LocalDateTime finalTo=to;MockHttpServletResponse second=new MockHttpServletResponse();
        ClientException sizeError=assertThrows(ClientException.class,()->service.export(second,new UserContext().setUserId(10L),20L,
                UsageExportType.RAW,UsageGranularity.DAY,from,finalTo,null,null,null,UsageBreakdownDimension.MODEL,UsageBreakdownOrder.TOTAL_TOKENS,10));
        assertEquals("A000956",sizeError.getErrorCode());assertEquals(0,second.getContentAsByteArray().length);
    }

    @Test
    void rawWorkbookContainsOnlyDocumentedSafeHeaders() throws Exception {
        UsageReportService reportService=mock(UsageReportService.class);TokenUsageMapper mapper=mock(TokenUsageMapper.class);
        UsageExportServiceImpl service=new UsageExportServiceImpl(reportService,mapper,new UsageReportingProperties(),new SimpleMeterRegistry());
        LocalDateTime from=LocalDateTime.of(2026,9,1,0,0),to=from.plusDays(1);
        UsageReportFilter filter=new UsageReportFilter(20L,null,null,null,from,to,UsageGranularity.DAY,true);
        when(reportService.resolveFilter(any(),anyLong(),any(),any(),any(),any(),any(),any())).thenReturn(filter);
        when(mapper.selectRawExportBatch(anyLong(),any(),any(),any(),any(),any(),any(),any(),anyInt())).thenReturn(List.of());
        MockHttpServletResponse response=new MockHttpServletResponse();
        service.export(response,new UserContext().setUserId(10L),20L,UsageExportType.RAW,UsageGranularity.DAY,from,to,
                null,null,null,UsageBreakdownDimension.MODEL,UsageBreakdownOrder.TOTAL_TOKENS,10);
        try(XSSFWorkbook workbook=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            String headers=workbook.getSheetAt(0).getRow(0).toString();
            assertTrue(headers.contains("请求时间")&&headers.contains("预估费用(分)"));
            assertFalse(headers.contains("usageDetailsJson"));assertFalse(headers.contains("apiKeyId"));
        }
    }

    @Test
    void concurrencyLimitRejectsPromptly() throws Exception {
        UsageReportService reportService=mock(UsageReportService.class);UsageReportingProperties properties=new UsageReportingProperties();
        properties.setExportMaxConcurrency(1);UsageExportServiceImpl service=new UsageExportServiceImpl(reportService,mock(TokenUsageMapper.class),properties,new SimpleMeterRegistry());
        LocalDateTime from=LocalDateTime.of(2026,9,1,0,0),to=from.plusDays(1);UsageReportFilter filter=new UsageReportFilter(20L,null,null,null,from,to,UsageGranularity.DAY,true);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(reportService.resolveFilter(any(),anyLong(),any(),any(),any(),any(),any(),any())).thenAnswer(invocation->{entered.countDown();release.await(5,TimeUnit.SECONDS);return filter;});
        UsageReportRespDTO report=new UsageReportRespDTO();report.setPoints(List.of());when(reportService.query(any(),anyLong(),any(),any(),any(),any(),any(),any())).thenReturn(report);
        CompletableFuture<Void> first=CompletableFuture.runAsync(()->service.export(new MockHttpServletResponse(),new UserContext().setUserId(10L),20L,
                UsageExportType.TIMESERIES,UsageGranularity.DAY,from,to,null,null,null,UsageBreakdownDimension.MODEL,UsageBreakdownOrder.TOTAL_TOKENS,10));
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        ClientException busy=assertThrows(ClientException.class,()->service.export(new MockHttpServletResponse(),new UserContext().setUserId(11L),20L,
                UsageExportType.TIMESERIES,UsageGranularity.DAY,from,to,null,null,null,UsageBreakdownDimension.MODEL,UsageBreakdownOrder.TOTAL_TOKENS,10));
        assertEquals("A000957",busy.getErrorCode());release.countDown();first.get(5,TimeUnit.SECONDS);
    }
}
