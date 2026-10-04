package com.yonagi.verse.controller;

import com.yonagi.verse.common.security.CurrentUserArgumentResolver;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.common.web.GlobalExceptionHandler;
import com.yonagi.verse.dto.req.NotificationListReqDTO;
import com.yonagi.verse.dto.resp.NotificationListRespDTO;
import com.yonagi.verse.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class NotificationControllerTest {
    private NotificationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(NotificationService.class);
        when(service.getNotificationList(eq(10L), any())).thenReturn(
                new NotificationListRespDTO().setTotal(0).setRecords(List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new NotificationController(service))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContextHolder.set(new UserContext().setUserId(10L));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void bindsAllFiltersIncludingUnreadZero() throws Exception {
        mvc.perform(get("/api/v1/notifications").param("pageNum", "2").param("pageSize", "5")
                        .param("type", "ANNOUNCEMENT").param("severity", "WARNING").param("isRead", "0"))
                .andExpect(jsonPath("$.code").value("0"));
        ArgumentCaptor<NotificationListReqDTO> captor = ArgumentCaptor.forClass(NotificationListReqDTO.class);
        verify(service).getNotificationList(eq(10L), captor.capture());
        NotificationListReqDTO query = captor.getValue();
        assertEquals(2, query.getPageNum());
        assertEquals(5, query.getPageSize());
        assertEquals("ANNOUNCEMENT", query.getType());
        assertEquals("WARNING", query.getSeverity());
        assertEquals(0, query.getIsRead());
    }

    @Test
    void oldPaginationOnlyRequestsStillWork() throws Exception {
        mvc.perform(get("/api/v1/notifications").param("pageNum", "1").param("pageSize", "10"))
                .andExpect(jsonPath("$.code").value("0"));
    }

    @ParameterizedTest
    @CsvSource({"type,ALL", "severity,ERROR", "isRead,2", "isRead,-1", "pageNum,0", "pageSize,0"})
    void rejectsInvalidFiltersAndPagination(String name, String value) throws Exception {
        mvc.perform(get("/api/v1/notifications").param(name, value))
                .andExpect(jsonPath("$.code").value("A000001"));
        verifyNoInteractions(service);
    }
}
