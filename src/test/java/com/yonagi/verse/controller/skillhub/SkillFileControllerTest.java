package com.yonagi.verse.controller.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dto.resp.SkillFileContentRespDTO;
import com.yonagi.verse.dto.resp.SkillFileRespDTO;
import com.yonagi.verse.dto.resp.SkillFilesRespDTO;
import com.yonagi.verse.service.skillhub.SkillFileQueryService;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用现有真实 MVC/Security 测试装配，依赖替身不代表真实 JWT/Redis 验证。 */
class SkillFileControllerTest {
    private static final String BASE = "/api/v1/skills/20/versions/70/files";
    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;
    private SkillFileQueryService service;

    @BeforeAll
    static void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "verse.security.ignore-paths=/api/v1/skills/**", "verse.frontend-baseurl=http://localhost:3000");
        context.register(SkillControllerContractTest.Wiring.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterAll
    static void close() {
        context.close();
    }

    @BeforeEach
    void resetService() {
        service = context.getBean(SkillFileQueryService.class);
        reset(service);
    }

    @Test
    void bothRoutesRequireAuthenticationEvenWhenIgnorePathsContainsSkills() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/content").param("path", "SKILL.md")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void listAndPreviewPreserveResultVersionAndNoStoreWithoutExposingObjectKeys() throws Exception {
        when(service.files(20L, 70L)).thenReturn(new SkillFilesRespDTO("70", "package", 1048576,
                List.of(new SkillFileRespDTO("a.txt", 2L, "hash"))));
        mvc.perform(get(BASE).header("X-Test-Identity", "JWT-user"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("0")).andExpect(jsonPath("$.data.versionId").value("70"))
                .andExpect(jsonPath("$.data.items[0].relativePath").value("a.txt"))
                .andExpect(jsonPath("$.data.archiveKey").doesNotExist());
        when(service.content(20L, 70L, "a.txt")).thenReturn(new SkillFileContentRespDTO(
                "70", "a.txt", 2L, "hash", "TEXT", null, "READY", "hi"));
        mvc.perform(get(BASE + "/content").param("path", "a.txt").header("X-Test-Identity", "JWT-user"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.content").value("hi")).andExpect(jsonPath("$.data.status").value("READY"));
    }

    @Test
    void missingParameterAndDomainFailuresKeepTheirHttpAndErrorContracts() throws Exception {
        mvc.perform(get(BASE + "/content").header("X-Test-Identity", "JWT-user"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        when(service.content(20L, 70L, "../x.txt")).thenThrow(new ClientException(SkillErrorCodeEnum.INVALID_INPUT));
        mvc.perform(get(BASE + "/content").param("path", "../x.txt").header("X-Test-Identity", "JWT-user"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("A001701"));
        when(service.files(20L, 70L)).thenThrow(new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE));
        mvc.perform(get(BASE).header("X-Test-Identity", "JWT-user")).andExpect(status().isNotFound());
        doThrow(new ServerException(SkillErrorCodeEnum.UNAVAILABLE)).when(service).files(20L, 70L);
        mvc.perform(get(BASE).header("X-Test-Identity", "JWT-user")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("B001701"));
    }
}
