package com.yonagi.verse.service.skillhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.dao.mapper.skillhub.SkillPublicQueryMapper;
import com.yonagi.verse.dao.projection.SkillOptionProjection;
import com.yonagi.verse.dao.projection.SkillPublicProjection;
import com.yonagi.verse.dao.projection.SkillRevisionTagProjection;
import com.yonagi.verse.dao.query.SkillSearchCondition;
import com.yonagi.verse.dto.req.SkillSearchReqDTO;
import com.yonagi.verse.dto.resp.SkillCardRespDTO;
import com.yonagi.verse.dto.resp.SkillDetailRespDTO;
import com.yonagi.verse.dto.resp.SkillPageRespDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 公开查询用例的可观察结果与输入边界，不模拟证明SQL或真实数据库正确。 */
class SkillQueryServiceTest {
    private final SkillPublicQueryMapper mapper = mock(SkillPublicQueryMapper.class);
    private final SkillQueryService service = new SkillQueryService(mapper, new ObjectMapper());

    @Test
    void emptyResultKeepsRequestedPageAndDoesNotLoadData() {
        SkillSearchReqDTO query = new SkillSearchReqDTO();
        query.setPage(3);
        query.setSize(24);
        SkillPageRespDTO<SkillCardRespDTO> page = service.search(query);
        assertEquals(3, page.page());
        assertEquals(24, page.size());
        assertEquals(0L, page.total());
        assertTrue(page.items().isEmpty());
        verify(mapper, never()).selectAvailablePage(any());
    }

    @Test
    void literalSearchEscapesWildcardsAndReturnsBatchTagsWithStableVersion() {
        SkillSearchReqDTO query = new SkillSearchReqDTO();
        query.setKeyword(" %_ ");
        query.setCategory("development");
        query.setTags(List.of("testing", "coding"));
        query.setPublisherId(20L);
        when(mapper.countAvailable(any())).thenReturn(1L);
        when(mapper.selectAvailablePage(any())).thenReturn(List.of(published()));
        when(mapper.selectRevisionTags(List.of(30L))).thenReturn(List.of(tag(30L, "coding")));

        SkillPageRespDTO<SkillCardRespDTO> page = service.search(query);
        ArgumentCaptor<SkillSearchCondition> captured = ArgumentCaptor.forClass(SkillSearchCondition.class);
        verify(mapper).countAvailable(captured.capture());
        SkillSearchCondition condition = captured.getValue();
        assertEquals("%_", condition.keyword());
        assertEquals("%!%!_%", condition.contains());
        assertEquals("!%!_%", condition.prefix());
        assertEquals(20L, condition.publisherId());
        assertEquals("development", condition.category());
        assertTrue(condition.relevance());
        assertEquals(12, page.size());
        SkillCardRespDTO card = page.items().getFirst();
        assertEquals("10", card.skillId());
        assertEquals("40", card.versionId());
        assertEquals("1.0.0", card.version());
        assertEquals("作者", card.publisher().publicName());
        assertEquals(List.of("coding"), card.tags());
    }

    @Test
    void updatedSortOverridesKeywordDefaultAndEmptyKeywordUsesUpdated() {
        SkillSearchReqDTO query = new SkillSearchReqDTO();
        query.setKeyword("hello!");
        query.setSort("updated");
        service.search(query);
        query.setKeyword("   ");
        query.setSort(null);
        service.search(query);
        ArgumentCaptor<SkillSearchCondition> captured = ArgumentCaptor.forClass(SkillSearchCondition.class);
        verify(mapper, org.mockito.Mockito.times(2)).countAvailable(captured.capture());
        assertEquals("%hello!!%", captured.getAllValues().getFirst().contains());
        assertFalse(captured.getAllValues().getFirst().relevance());
        assertNull(captured.getAllValues().getLast().keyword());
        assertFalse(captured.getAllValues().getLast().relevance());
    }

    @Test
    void rejectsUnsupportedPageSizeSortAndSourceRatherThanPretendingEmpty() {
        SkillSearchReqDTO query = new SkillSearchReqDTO();
        query.setSize(100);
        assertThrows(ClientException.class, () -> service.search(query));
        query.setSize(12);
        query.setSort("downloads");
        assertThrows(ClientException.class, () -> service.search(query));
        query.setSort("updated");
        query.setSource("GITHUB");
        assertThrows(ClientException.class, () -> service.search(query));
        query.setSource("ZIP");
        query.setTags(List.of("a", "b", "c", "d", "e", "f"));
        assertThrows(ClientException.class, () -> service.search(query));
    }

    @Test
    void unavailablePublishedSkillReturnsNoBodyDeclarationsOrArtifactMetadata() {
        when(mapper.selectPublishedPlaceholder(10L)).thenReturn(published());
        when(mapper.selectRevisionTags(List.of(30L))).thenReturn(List.of());
        SkillDetailRespDTO detail = service.detail(10L);
        assertEquals("UNAVAILABLE", detail.availability());
        assertEquals("测试Skill", detail.skill().name());
        assertNull(detail.skillMarkdown());
        assertNull(detail.license());
        assertNull(detail.dependencies());
        assertNull(detail.archiveBytes());
        assertNull(detail.sha256());
    }

    @Test
    void visibleDetailUsesTheSameVersionSnapshotAndDoesNotReadStorage() {
        when(mapper.selectAvailableDetail(10L)).thenReturn(published());
        when(mapper.selectRevisionTags(List.of(30L))).thenReturn(List.of());
        SkillDetailRespDTO detail = service.detail(10L);
        assertEquals("AVAILABLE", detail.availability());
        assertEquals("40", detail.skill().versionId());
        assertEquals("# Skill", detail.skillMarkdown());
        assertEquals("MIT", detail.license());
        assertEquals("无额外依赖", detail.dependencies());
        assertEquals(123L, detail.archiveBytes());
        assertEquals("a".repeat(64), detail.sha256());
    }

    @Test
    void privateOrMissingSkillAndLookupHaveOneInaccessibleContract() {
        when(mapper.selectPublishedId("someone", "unknown")).thenReturn(null);
        assertThrows(ClientException.class, () -> service.detail(99L));
        assertThrows(ClientException.class, () -> service.lookup("someone", "unknown"));
        assertThrows(ClientException.class, () -> service.lookup("x".repeat(33), "unknown"));
        when(mapper.selectPublishedId("author", "skill")).thenReturn(10L);
        assertEquals("10", service.lookup("author", "skill"));
    }

    @Test
    void malformedSnapshotAndDependencyFailureAreNotEmptyOrSuccessfulViews() {
        SkillPublicProjection row = published();
        row.setDeclarationsJson("broken");
        when(mapper.selectAvailableDetail(10L)).thenReturn(row);
        assertThrows(ServerException.class, () -> service.detail(10L));
        row.setDeclarationsJson("{}");
        assertThrows(ServerException.class, () -> service.detail(10L));
        when(mapper.countAvailable(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> service.search(new SkillSearchReqDTO()));
    }

    @Test
    void optionsReportAuthoritativeLimitsAndTaxonomy() {
        SkillOptionProjection category = new SkillOptionProjection();
        category.setId("development");
        category.setName("开发工具");
        when(mapper.selectCategories()).thenReturn(List.of(category));
        when(mapper.selectTags()).thenReturn(List.of());
        assertEquals(20L * 1024 * 1024, service.options().maxArchiveBytes());
        assertEquals("开发工具", service.options().categories().getFirst().name());
        assertEquals(500, service.options().maxFiles());
    }

    private SkillPublicProjection published() {
        SkillPublicProjection row = new SkillPublicProjection();
        row.setSkillId(10L);
        row.setPublisherId(20L);
        row.setHandle("author");
        row.setPublicName("作者");
        row.setSlug("test-skill");
        row.setName("测试Skill");
        row.setSummary("介绍");
        row.setCategoryId("development");
        row.setRevisionId(30L);
        row.setVersionId(40L);
        row.setVersionLabel("1.0.0");
        row.setPublishedAt(LocalDateTime.of(2026, 10, 24, 0, 0));
        row.setSkillMarkdown("# Skill");
        row.setDeclarationsJson("{\"license\":\"MIT\",\"dependencies\":\"无额外依赖\"}");
        row.setArchiveBytes(123L);
        row.setArchiveSha256("a".repeat(64));
        return row;
    }

    private SkillRevisionTagProjection tag(Long revision, String value) {
        SkillRevisionTagProjection row = new SkillRevisionTagProjection();
        row.setRevisionId(revision);
        row.setTagId(value);
        return row;
    }
}
