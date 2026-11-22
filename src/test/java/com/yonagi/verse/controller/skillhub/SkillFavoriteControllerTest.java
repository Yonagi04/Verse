package com.yonagi.verse.controller.skillhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.security.*;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.skillhub.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SkillFavoriteControllerTest {
    private static final String BASE = "/api/v1/skills/me";
    private static final String KEY = "12345678-1234-1234-1234-123456789abc";
    private final SkillFavoriteQueryService queries = mock(SkillFavoriteQueryService.class);
    private final SkillFavoriteCommandService commands = mock(SkillFavoriteCommandService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new SkillFavoriteController(queries, commands))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new SkillExceptionAdvice(), new SkillRequestBodyAdvice(new ObjectMapper())).build();
        UserContextHolder.set(new UserContext().setUserId(2L));
    }
    @AfterEach void clear() { UserContextHolder.clear(); }

    @Test void privateQueriesBindTrustedActorNormalizeParametersAndKeepEnvelope() throws Exception {
        when(queries.folders(2L)).thenReturn(new SkillFavoriteFoldersRespDTO(List.of(), 0L, 20, 1000));
        mvc.perform(get(BASE + "/favorite-folders")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        when(queries.page(eq(2L), any())).thenReturn(new SkillFavoritePageRespDTO(1, 12, 0L, List.of()));
        mvc.perform(get(BASE + "/favorites")).andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value(12));
        when(queries.statuses(2L, List.of(20L, 21L))).thenReturn(List.of(new SkillFavoriteStatusRespDTO("20", null, null, null, null)));
        mvc.perform(get(BASE + "/favorites/statuses").param("skillIds", "21", "20", "21"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].skillId").value("20"));
        mvc.perform(get(BASE + "/favorites").param("size", "100")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/favorites/statuses").param("skillIds", java.util.Collections.nCopies(25, "20").toArray(String[]::new)))
                .andExpect(status().isBadRequest());
    }

    @Test void createsReturn201AndReplay200WithStringIds() throws Exception {
        var folder = new SkillFavoriteFolderReceiptRespDTO("9007199254740993", true, 0);
        when(commands.createFolder(2L, "Dev", KEY)).thenReturn(new SkillFavoriteCommandService.FolderCreation(folder, true),
                new SkillFavoriteCommandService.FolderCreation(folder, false));
        for (int status : new int[]{201, 200}) {
            mvc.perform(post(BASE + "/favorite-folders").header("Idempotency-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Dev\"}"))
                    .andExpect(status().is(status)).andExpect(jsonPath("$.data.folderId").value("9007199254740993"));
        }
        var receipt = receipt();
        when(commands.create(2L, 20L, 0L, KEY)).thenReturn(new SkillFavoriteCommandService.Creation(receipt, true),
                new SkillFavoriteCommandService.Creation(receipt, false));
        for (int status : new int[]{201, 200}) {
            mvc.perform(post(BASE + "/favorites").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"skillId\":\"20\",\"folderId\":\"0\"}"))
                    .andExpect(status().is(status)).andExpect(jsonPath("$.data.favoriteId").value("9007199254740993"));
        }
    }

    @Test void rejectsMissingHeaderMalformedBodyAndForgedOwnership() throws Exception {
        mvc.perform(post(BASE + "/favorites").contentType(MediaType.APPLICATION_JSON).content("{\"skillId\":20,\"folderId\":0}"))
                .andExpect(status().isBadRequest());
        for (String body : List.of("{\"skillId\":20}", "{\"skillId\":20,\"folderId\":-1}",
                "{\"skillId\":20,\"folderId\":0,\"userId\":1}", "{broken", "{} {}")) {
            mvc.perform(post(BASE + "/favorites").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(commands);
    }

    @Test void manageRoutesAndExpiredUndoKeepDomainHttpSemantics() throws Exception {
        when(commands.renameFolder(2L, 10L, "New", KEY)).thenReturn(new SkillFavoriteFolderReceiptRespDTO("10", true, 0));
        mvc.perform(put(BASE + "/favorite-folders/10").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(true));
        when(commands.deleteFolder(2L, 10L, KEY)).thenReturn(new SkillFavoriteFolderReceiptRespDTO("10", true, 3));
        mvc.perform(delete(BASE + "/favorite-folders/10").header("Idempotency-Key", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.migrated").value(3));
        when(commands.move(2L, 100L, 0L, KEY)).thenReturn(receipt());
        mvc.perform(put(BASE + "/favorites/100").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"folderId\":0}")).andExpect(status().isOk());
        when(commands.cancel(2L, 100L, KEY)).thenReturn(receipt());
        mvc.perform(delete(BASE + "/favorites/100").header("Idempotency-Key", KEY)).andExpect(status().isOk());
        when(commands.restore(2L, 100L, KEY)).thenReturn(receipt());
        mvc.perform(post(BASE + "/favorites/100/restore").header("Idempotency-Key", KEY)).andExpect(status().isOk());
        when(commands.restore(2L, 100L, KEY)).thenThrow(new ClientException(SkillErrorCodeEnum.FAVORITE_UNDO_EXPIRED));
        mvc.perform(post(BASE + "/favorites/100/restore").header("Idempotency-Key", KEY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("A001710"));
    }

    private static SkillFavoriteReceiptRespDTO receipt() {
        return new SkillFavoriteReceiptRespDTO("CREATED", "9007199254740993", "20", "0", null, false, null, false);
    }
}
