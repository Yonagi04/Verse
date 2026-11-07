package com.yonagi.verse.controller;

import com.yonagi.verse.common.config.UsageReportingProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.UsageBreakdownDimension;
import com.yonagi.verse.common.enums.UsageBreakdownOrder;
import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.enums.UsageReportingErrorCodeEnum;
import com.yonagi.verse.common.security.CurrentUserArgumentResolver;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.common.web.GlobalExceptionHandler;
import com.yonagi.verse.dao.mapper.ApiKeyMapper;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.TokenUsageMapper;
import com.yonagi.verse.dao.projection.UsageRawExportRow;
import com.yonagi.verse.dto.resp.UsageBreakdownRespDTO;
import com.yonagi.verse.service.UsageExportService;
import com.yonagi.verse.service.UsageReportService;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.impl.UsageExportServiceImpl;
import com.yonagi.verse.service.reporting.UsageExportTarget;
import com.yonagi.verse.service.reporting.UsageReportAccessPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.XContentTypeOptionsHeaderWriter;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.io.OutputStream;
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
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String FILENAME = "usage-raw-20261107-164000.xlsx";
    private static final byte[] FILE_BYTES = {0x50, 0x4b, 0x03, 0x04};
    private static final LocalDateTime RAW_FROM = LocalDateTime.of(2026, 9, 1, 0, 0);

    @Test
    void downloadPublishesWorkbookHeadersWithTheFirstBytes() throws Exception {
        UsageExportService export = mock(UsageExportService.class);
        stubFileOutput(export, false, false);

        performExport(export).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(XLSX))
                .andExpect(content().bytes(FILE_BYTES))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(result -> assertEquals(FILENAME, ContentDisposition.parse(
                        result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).getFilename()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fileFailureReturnsJsonOnlyBeforeCommit(boolean committed) throws Exception {
        UsageExportService export = mock(UsageExportService.class);
        stubFileOutput(export, committed, true);
        ResultActions result = performExport(export).andExpect(status().isOk())
                .andExpect(header().stringValues(HttpHeaders.VARY, "Origin", "Accept-Encoding"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        if (committed) {
            result.andExpect(content().contentTypeCompatibleWith(XLSX)).andExpect(content().bytes(FILE_BYTES));
        } else {
            result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
                    .andExpect(jsonPath("$.code").value(UsageReportingErrorCodeEnum.EXPORT_FAILED.code()));
        }
    }

    private static void stubFileOutput(UsageExportService export, boolean committed, boolean fail) {
        doAnswer(call -> {
            UsageExportTarget target = call.getArgument(0);
            OutputStream output = target.outputStream();
            target.prepare(FILENAME);
            output.write(FILE_BYTES);
            if (committed) {
                output.flush();
            }
            if (fail) {
                throw new ServerException("生成用量报表失败", UsageReportingErrorCodeEnum.EXPORT_FAILED);
            }
            return null;
        }).when(export).export(any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyInt());
    }

    private static ResultActions performExport(UsageExportService export) throws Exception {
        return performExport(export, get("/api/v1/usage/20/export").param("type", "raw"));
    }

    private static ResultActions performExport(UsageExportService export, MockHttpServletRequestBuilder request)
            throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UsageReportController(mock(UsageReportService.class), export))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new HeaderWriterFilter(List.of(new XContentTypeOptionsHeaderWriter())),
                        (servletRequest, response, chain) -> {
                            HttpServletResponse httpResponse = (HttpServletResponse) response;
                            httpResponse.addHeader(HttpHeaders.VARY, "Origin");
                            httpResponse.addHeader(HttpHeaders.VARY, "Accept-Encoding");
                            chain.doFilter(servletRequest, response);
                        })
                .build();
        UserContextHolder.set(new UserContext().setUserId(10L).setCurrentTenantId(20L));
        try {
            return mvc.perform(request);
        } finally {
            UserContextHolder.clear();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {68, 90})
    void rawExportDownloadsCrossMonthRangesWithinTheDefaultLimit(int days) throws Exception {
        TokenUsageMapper mapper = mock(TokenUsageMapper.class);
        LocalDateTime to = RAW_FROM.plusDays(days);
        when(mapper.countRawExport(20L, 10L, null, null, RAW_FROM, to)).thenReturn(0L);
        when(mapper.selectRawExportBatch(20L, 10L, null, null, RAW_FROM, to, null, null, 1000))
                .thenReturn(List.of());

        performExport(rawExportService(mapper, new UsageReportingProperties()), rawExportRequest(to))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(XLSX))
                .andExpect(header().exists(HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(result -> assertTrue(result.getResponse().getContentAsByteArray().length > 0));
    }

    @ParameterizedTest
    @CsvSource({",90,1,90", ",367,0,90", "60,61,0,60"})
    void rawExportRejectsOverlongRangesWithTheConfiguredLimit(Integer configuredDays, int days, int extraSeconds,
                                                             int expectedDays) throws Exception {
        UsageReportingProperties properties = new UsageReportingProperties();
        if (configuredDays != null) {
            properties.setRawExportMaxRangeDays(configuredDays);
        }
        TokenUsageMapper mapper = mock(TokenUsageMapper.class);
        LocalDateTime to = RAW_FROM.plusDays(days).plusSeconds(extraSeconds);

        performExport(rawExportService(mapper, properties), rawExportRequest(to))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(jsonPath("$.code").value(UsageReportingErrorCodeEnum.EXPORT_INVALID.code()))
                .andExpect(jsonPath("$.message").value("导出统计报表的天数范围不能超过 " + expectedDays + " 天"));
        verifyNoInteractions(mapper);
    }

    private static MockHttpServletRequestBuilder rawExportRequest(LocalDateTime to) {
        return get("/api/v1/usage/20/export").param("type", "raw").param("granularity", "month")
                .param("from", RAW_FROM.toString()).param("to", to.toString());
    }

    private static UsageExportService rawExportService(TokenUsageMapper mapper, UsageReportingProperties properties) {
        UserTenantService tenants = mock(UserTenantService.class);
        when(tenants.isUserJoinedTenant(10L, 20L)).thenReturn(true);
        when(tenants.getRoleByUserIdAndTenantId(10L, 20L)).thenReturn("MEMBER");
        UsageReportAccessPolicy reportAccess = new UsageReportAccessPolicy(tenants, properties,
                mock(ApiKeyMapper.class), mock(LlmServiceMapper.class));
        return new UsageExportServiceImpl(mock(UsageReportService.class), reportAccess, mapper, properties,
                new SimpleMeterRegistry());
    }

    @Test
    void rawExportOverflowAfterFullBatchReturnsJsonWithoutAnAttachment() throws Exception {
        TokenUsageMapper mapper = mock(TokenUsageMapper.class);
        UsageReportingProperties properties = new UsageReportingProperties();
        properties.setExportBatchSize(1);
        properties.setExportMaxRows(1);
        LocalDateTime from = RAW_FROM;
        LocalDateTime to = from.plusDays(1);
        when(mapper.countRawExport(20L, 10L, null, null, from, to)).thenReturn(1L);
        UsageRawExportRow first = new UsageRawExportRow();
        first.setId(1L);
        first.setRequestStartedAt(from);
        UsageRawExportRow extra = new UsageRawExportRow();
        extra.setId(2L);
        extra.setRequestStartedAt(from);
        when(mapper.selectRawExportBatch(eq(20L), eq(10L), isNull(), isNull(), eq(from), eq(to),
                any(), any(), eq(1))).thenReturn(List.of(first), List.of(extra));

        performExport(rawExportService(mapper, properties), rawExportRequest(to))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(jsonPath("$.code").value(UsageReportingErrorCodeEnum.EXPORT_TOO_LARGE.code()));
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
