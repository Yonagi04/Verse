package com.yonagi.verse.controller.skillhub;

import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.resp.SkillImportTaskRespDTO;
import com.yonagi.verse.dto.resp.SkillGithubCandidatesRespDTO;
import com.yonagi.verse.service.skillhub.SkillDraftFileService;
import com.yonagi.verse.service.skillhub.SkillGithubImportService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SkillGithubImportControllerTest {
    private final SkillGithubImportService service=mock(SkillGithubImportService.class);
    private final SkillDraftFileService files=mock(SkillDraftFileService.class);
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new SkillGithubImportController(service,files))
                .setControllerAdvice(new SkillExceptionAdvice(),new SkillRequestBodyAdvice(new com.fasterxml.jackson.databind.ObjectMapper()))
                .build();
        UserContext actor=new UserContext(); actor.setUserId(1L); UserContextHolder.set(actor);
    }
    @AfterEach void clear(){UserContextHolder.clear();}

    @Test void jsonCreateIsAcceptedAndActorCannotBeForged() throws Exception {
        when(service.create(eq(1L),eq(30L),any(),eq("key"))).thenReturn(new SkillImportTaskRespDTO("50","30","QUEUED",3L,null,null,0,OffsetDateTime.now()));
        mvc.perform(post("/api/v1/skills/drafts/30/github-imports").header("Idempotency-Key","key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2,\"repositoryUrl\":\"https://github.com/a/b\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.jobId").value("50"));
        mvc.perform(post("/api/v1/skills/drafts/30/github-imports").header("Idempotency-Key","key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2,\"repositoryUrl\":\"https://github.com/a/b\",\"userId\":2}"))
                .andExpect(status().isBadRequest());
    }
    @Test void sourceAndSelectionHaveExplicitRoutesAndValidatePayloads() throws Exception {
        mvc.perform(post("/api/v1/skills/imports/50/source-confirmations").header("Idempotency-Key","key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2,\"sourceOptionId\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/skills/imports/50/selections").header("Idempotency-Key","key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2,\"scanDigest\":\"short\",\"candidateId\":\"one\"}"))
                .andExpect(status().isBadRequest());
        when(service.candidates(1L,50L,1,20)).thenReturn(new SkillGithubCandidatesRespDTO(1,20,0,List.of(),null));
        mvc.perform(get("/api/v1/skills/imports/50/candidates")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }
    @Test void anonymousAndApiKeyAreRejectedBeforeServiceInvocation() throws Exception {
        UserContextHolder.clear();
        mvc.perform(get("/api/v1/skills/imports/50/candidates")).andExpect(status().isNotFound());
        UserContext actor=new UserContext(); actor.setUserId(1L); actor.setApiKeyId(5L); UserContextHolder.set(actor);
        mvc.perform(get("/api/v1/skills/imports/50/candidates")).andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
    @Test void acceptedConfirmationSelectionAndPrivateFilesPreserveCommandAndSnapshotContracts() throws Exception {
        var receipt=new SkillImportTaskRespDTO("50","30","QUEUED",4L,null,null,0,OffsetDateTime.now());
        when(service.confirm(eq(1L),eq(50L),any(),eq("same-key"))).thenReturn(receipt);
        when(service.select(eq(1L),eq(50L),any(),eq("same-key"))).thenReturn(receipt);
        mvc.perform(post("/api/v1/skills/imports/50/source-confirmations").header("Idempotency-Key","same-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":3,\"sourceOptionId\":\"option\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.draftRevision").value(4));
        mvc.perform(post("/api/v1/skills/imports/50/selections").header("Idempotency-Key","same-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":3,\"scanDigest\":\""+"c".repeat(64)+"\",\"candidateId\":\"one\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.jobId").value("50"));
        when(files.files(1L,30L,1,50)).thenReturn(new com.yonagi.verse.dto.resp.SkillDraftFilesRespDTO(1,50,0,List.of(),"40","a".repeat(64)));
        mvc.perform(get("/api/v1/skills/drafts/30/files")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.artifactId").value("40")).andExpect(jsonPath("$.data.contentSha256").value("a".repeat(64)));
        UserContextHolder.set(new UserContext());
        mvc.perform(get("/api/v1/skills/drafts/30/files")).andExpect(status().isNotFound());
        verify(files,times(1)).files(1L,30L,1,50);
    }
}
