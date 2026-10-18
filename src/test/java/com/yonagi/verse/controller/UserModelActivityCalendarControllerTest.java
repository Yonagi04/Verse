package com.yonagi.verse.controller;

import com.yonagi.verse.common.security.*;
import com.yonagi.verse.common.web.GlobalExceptionHandler;
import com.yonagi.verse.service.user.activity.UserModelActivityCalendarQueryService;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserModelActivityCalendarControllerTest {
    final UserModelActivityCalendarQueryService query=mock(UserModelActivityCalendarQueryService.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        UserContextHolder.set(new UserContext().setUserId(99L));
        mvc=MockMvcBuilders.standaloneSetup(new UserModelActivityCalendarController(query)).setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void cleanup() { UserContextHolder.clear(); }
    @Test void noTenantIsNeededAndTrustedSelfIdentityReachesCalendarQuery() throws Exception {
        mvc.perform(get("/api/v1/users/me/activity-calendar").param("view","YEAR").param("year","2024")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")); verify(query).getCalendar(99L,"YEAR",2024);
    }
    @Test void forgedIdentityParametersNeverReachCalendarQuery() throws Exception {
        mvc.perform(get("/api/v1/users/me/activity-calendar").param("userId","1").param("tenantId","2")).andExpect(jsonPath("$.code").value("A001604")); verifyNoInteractions(query);
    }
}
