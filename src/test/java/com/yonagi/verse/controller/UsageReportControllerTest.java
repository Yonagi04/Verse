package com.yonagi.verse.controller;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UsageBreakdownDimension;
import com.yonagi.verse.common.enums.UsageBreakdownOrder;
import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dto.resp.UsageBreakdownRespDTO;
import com.yonagi.verse.service.UsageExportService;
import com.yonagi.verse.service.UsageReportService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UsageReportControllerTest {
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
