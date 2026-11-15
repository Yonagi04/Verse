package com.yonagi.verse.controller.skillhub;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.resp.SkillPublicationResultRespDTO;
import com.yonagi.verse.dto.resp.SkillVersionPageRespDTO;
import com.yonagi.verse.service.skillhub.SkillGithubImportService;
import com.yonagi.verse.service.skillhub.SkillVersionFileService;
import com.yonagi.verse.service.skillhub.SkillVersionPublicationService;
import com.yonagi.verse.service.skillhub.SkillVersionQueryService;
import com.yonagi.verse.service.skillhub.SkillVersionWorkspaceService;
import com.yonagi.verse.service.skillhub.port.SkillPublicQueryRateLimiter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

/** HTTP形状、可信身份接入与错误映射，不替代Service的所有权测试。 */
class SkillVersionControllerTest {
    private final SkillVersionQueryService history = mock(SkillVersionQueryService.class);
    private final SkillVersionPublicationService publication =
            mock(SkillVersionPublicationService.class);
    private final SkillVersionWorkspaceService workspace = mock(SkillVersionWorkspaceService.class);
    private final SkillVersionFileService files = mock(SkillVersionFileService.class);
    private final SkillPublicQueryRateLimiter limiter = mock(SkillPublicQueryRateLimiter.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new SkillVersionController(history, publication, files, limiter),
                                new SkillVersionWorkspaceController(
                                        workspace,
                                        mock(SkillGithubImportService.class),
                                        files,
                                        limiter))
                        .setControllerAdvice(
                                new SkillExceptionAdvice(),
                                new SkillRequestBodyAdvice(new ObjectMapper()))
                        .build();
        UserContext context = new UserContext();
        context.setUserId(1L);
        UserContextHolder.set(context);
    }

    @AfterEach
    void clear() {
        UserContextHolder.clear();
    }

    @Test
    void privateTimelineUsesResultAndNoStoreAndStringSequence() throws Exception {
        when(history.timeline(1L, 20L, 1, 20, null))
                .thenReturn(new SkillVersionPageRespDTO(1, 20, 0L, "9007199254740993", List.of()));
        mvc.perform(get("/api/v1/skills/20/versions"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.headSequence").value("9007199254740993"));
    }

    @Test
    void updateCannotAcceptBasicInformationOrSourceConfiguration() throws Exception {
        for (String json :
                List.of(
                        "{\"revision\":\"2\",\"releaseNotes\":\"说明\",\"name\":\"改名\"}",
                        "{\"revision\":\"2\",\"releaseNotes\":\"说明\",\"repositoryUrl\":\"https://github.com/evil/repo\"}")) {
            mvc.perform(
                            put("/api/v1/skills/20/workspace")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(workspace);
    }

    @Test
    void receiptUnconfirmedIsExplicitAndPrivateDenialDoesNotRevealVersion() throws Exception {
        when(publication.result(1L, 20L, "key"))
                .thenReturn(new SkillPublicationResultRespDTO("UNCONFIRMED", null));
        mvc.perform(get("/api/v1/skills/20/publication-results/key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNCONFIRMED"));
        when(history.detail(1L, 20L, 70L))
                .thenThrow(new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE));
        mvc.perform(get("/api/v1/skills/20/versions/70"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("A001702"));
    }

    @Test
    void missingIdentityAndMalformedPublicationCannotInvokeApplicationUseCase() throws Exception {
        mvc.perform(
                        post("/api/v1/skills/20/versions")
                                .header("Idempotency-Key", "key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"workspaceRevision\":-1,\"expectedSkillRevision\":0,\"type\":\"RELEASE\",\"confirmationDigest\":\"bad\"}"))
                .andExpect(status().isBadRequest());
        UserContextHolder.clear();
        mvc.perform(get("/api/v1/skills/20/versions")).andExpect(status().isNotFound());
        verifyNoInteractions(publication, history);
    }
}
