package com.yonagi.verse.controller.skillhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.security.*;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.skillhub.*;
import com.yonagi.verse.service.skillhub.port.SkillPublicQueryRateLimiter;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实MVC绑定和错误契约；认证Filter整体边界复用既有Skill契约测试。 */
class SkillCommentControllerTest {
    private final SkillCommentQueryService queries = mock(SkillCommentQueryService.class);
    private final SkillCommentCommandService commands = mock(SkillCommentCommandService.class);
    private MockMvc mvc;
    private static final String BASE = "/api/v1/skills/20/comments";

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new SkillCommentController(queries, commands,
                mock(SkillPublicQueryRateLimiter.class)))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new SkillExceptionAdvice(), new SkillRequestBodyAdvice(new ObjectMapper()))
                .build();
        UserContextHolder.set(new UserContext().setUserId(2L));
    }
    @AfterEach void clearIdentity() { UserContextHolder.clear(); }

    @Test void defaultsAndIndependentReplyPagingKeepResultEnvelope() throws Exception {
        when(queries.roots(2L, 20L, "hot", 1, 10)).thenReturn(new SkillPageRespDTO<>(1, 10, 0L, List.of()));
        mvc.perform(get(BASE)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.items").isEmpty());
        when(queries.replies(2L, 20L, 100L, 1, 20)).thenReturn(new SkillPageRespDTO<>(1, 20, 0L, List.of()));
        mvc.perform(get(BASE + "/100/replies")).andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value(20));
    }
    @Test void createUsesTrustedIdentityAndDifferentiatesReplayStatus() throws Exception {
        var receipt = new SkillCommentCreatedRespDTO("9007199254740993", "9007199254740993", "20");
        when(commands.create(eq(2L), eq(20L), any(), eq("key")))
                .thenReturn(new SkillCommentCommandService.Creation(receipt, true))
                .thenReturn(new SkillCommentCommandService.Creation(receipt, false));
        for (int status : new int[]{201, 200}) {
            mvc.perform(post(BASE).header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"评论\"}"))
                    .andExpect(status().is(status)).andExpect(jsonPath("$.data.commentId").value("9007199254740993"))
                    .andExpect(jsonPath("$.data.content").doesNotExist());
        }
    }
    @Test void rejectsMissingHeaderUnknownAuthorMalformedJsonAndBlankContent() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"评论\"}"))
                .andExpect(status().isBadRequest());
        for (String body : List.of("{\"content\":\"评论\",\"userId\":9}", "{\"content\":\"\"}", "{broken", "{\"content\":\"x\"} {}")) {
            mvc.perform(post(BASE).header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(commands);
    }
    @Test void reactionAndDeleteUseStableResourceAndErrorContracts() throws Exception {
        when(commands.react(2L, 20L, 100L, "DISLIKE")).thenReturn(new SkillCommentReactionRespDTO("100", "DISLIKE", 3L));
        mvc.perform(put(BASE + "/100/reaction").contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"DISLIKE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.likeCount").value(3))
                .andExpect(jsonPath("$.data.dislikeCount").doesNotExist());
        mvc.perform(put(BASE + "/100/reaction").contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"bad\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(BASE + "/100")).andExpect(status().isOk());
        doThrow(new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE)).when(commands).delete(2L, 20L, 101L);
        mvc.perform(delete(BASE + "/101")).andExpect(status().isNotFound());
        doThrow(new ClientException(SkillErrorCodeEnum.CONFLICT)).when(commands).delete(2L, 20L, 102L);
        mvc.perform(delete(BASE + "/102")).andExpect(status().isConflict());
    }
}
