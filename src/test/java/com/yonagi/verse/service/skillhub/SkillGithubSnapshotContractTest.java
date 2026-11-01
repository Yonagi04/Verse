package com.yonagi.verse.service.skillhub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.dto.skillhub.SkillSourceDTO;
import com.yonagi.verse.dto.skillhub.SkillGithubSourceOptionDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 校验快照边界和审批绑定业务，不测试DTO生成行为。 */
class SkillGithubSnapshotContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final SkillGithubSnapshotCodec codec = new SkillGithubSnapshotCodec(json);
    private final SkillPublicationSnapshotCodec publication = new SkillPublicationSnapshotCodec(json);

    @Test
    void unknownFieldsTrailingTokensNullListsAndUtf8OverflowCannotSilentlyChangeSnapshot() {
        assertThrows(ServerException.class, () -> codec.encode("中文", 6));
        assertThrows(ServerException.class, () -> codec.decode("{\"unknown\":true}", SkillSourceDTO.class));
        assertThrows(ServerException.class, () -> codec.decode(codec.encode(source("a", "one"), 8192) + " {}", SkillSourceDTO.class));
        var type = new TypeReference<List<SkillGithubSourceOptionDTO>>() { };
        assertEquals(List.of(), codec.list(null, type));
        assertThrows(ServerException.class, () -> codec.list("null", type));
        assertThrows(ServerException.class, () -> codec.list("[null]", type));
        assertThrows(ServerException.class, () -> codec.list("{}", type));
        assertThrows(ServerException.class, () -> codec.source("GITHUB", null));
        assertEquals("ZIP", codec.source(null, null).type());
        assertEquals(source("a", "one"), codec.source("GITHUB", codec.encode(source("a", "one"), 8192)));
    }

    @Test
    void approvalHashFreezesCommitDirectorySourceAndContentWithoutRewritingHistoricalHash() {
        var metadata = SkillTestFixture.metadata();
        String first = publication.publicationHash(metadata, "content", "GITHUB", codec.encode(source("a", "one"), 8192));
        assertEquals(first, publication.publicationHash(metadata, "content", "GITHUB", codec.encode(source("a", "one"), 8192)));
        assertNotEquals(first, publication.publicationHash(metadata, "content", "GITHUB", codec.encode(source("b", "one"), 8192)));
        assertNotEquals(first, publication.publicationHash(metadata, "content", "GITHUB", codec.encode(source("a", "two"), 8192)));
        assertNotEquals(first, publication.publicationHash(metadata, "different", "GITHUB", codec.encode(source("a", "one"), 8192)));
        assertNotEquals(first, publication.publicationHash(metadata, "content", "ZIP", null));
        var reset = publication.decode(publication.clearConfirmations(publication.encode(metadata)));
        assertFalse(reset.distributionAuthorized()); assertFalse(reset.declarationsConfirmed());
        assertEquals(metadata.name(), reset.name()); assertEquals(metadata.license(), reset.license());
        assertNull(publication.clearConfirmations(null));
    }

    private SkillSourceDTO source(String character, String directory) {
        return new SkillSourceDTO(1, "GITHUB", "https://github.com/a/b", "99", "main", "BRANCH", character.repeat(40), directory);
    }
}
