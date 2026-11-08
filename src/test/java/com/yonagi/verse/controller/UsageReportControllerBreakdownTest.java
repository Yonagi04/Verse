package com.yonagi.verse.controller;

import com.yonagi.verse.common.enums.UsageBreakdownOrder;
import com.yonagi.verse.common.enums.UsageReportingErrorCodeEnum;
import com.yonagi.verse.common.security.CurrentUserArgumentResolver;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.common.web.GlobalExceptionHandler;
import com.yonagi.verse.dto.resp.UsageBreakdownRespDTO;
import com.yonagi.verse.service.UsageExportService;
import com.yonagi.verse.service.UsageReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ResourceLock(Resources.LOCALE)
class UsageReportControllerBreakdownTest {

    private final UsageReportService reports = mock(UsageReportService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new UsageReportController(reports, mock(UsageExportService.class)))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        UserContextHolder.set(new UserContext().setUserId(10L).setCurrentTenantId(20L));
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @ParameterizedTest
    @CsvSource({
            "estimatedCostFen, ESTIMATED_COST_FEN",
            "estimated-cost-fen, ESTIMATED_COST_FEN",
            "ESTIMATED_COST_FEN, ESTIMATED_COST_FEN",
            "requestCount, REQUEST_COUNT",
            "' ', TOTAL_TOKENS"
    })
    void resolvesPublicSortAliasesIndependentlyOfDefaultLocale(String input, UsageBreakdownOrder expected)
            throws Exception {
        when(reports.breakdown(any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenAnswer(invocation -> {
                    UsageBreakdownOrder order = invocation.getArgument(9);
                    UsageBreakdownRespDTO result = new UsageBreakdownRespDTO();
                    result.setOrderBy(order.name());
                    return result;
                });
        Locale previous = Locale.getDefault();
        Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            mvc.perform(get("/api/v1/usage/20/breakdown").param("orderBy", input))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.orderBy").value(expected.name()));
        } finally {
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
            Locale.setDefault(Locale.Category.FORMAT, previousFormat);
        }
    }

    @Test
    void rejectsUnknownSortAliasBeforeQueryingTheReport() throws Exception {
        mvc.perform(get("/api/v1/usage/20/breakdown").param("orderBy", "unsupported"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(UsageReportingErrorCodeEnum.FILTER_INVALID.code()));
        verifyNoInteractions(reports);
    }
}
