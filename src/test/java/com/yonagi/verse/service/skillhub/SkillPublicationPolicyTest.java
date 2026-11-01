package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.mapper.skillhub.SkillPublicQueryMapper;
import com.yonagi.verse.dao.projection.SkillOptionProjection;
import com.yonagi.verse.dto.skillhub.SkillPublicationMetadataDTO;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillPublicationPolicyTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private final SkillPublicQueryMapper mapper = mock(SkillPublicQueryMapper.class);
    private final SkillPublicationPolicy policy = new SkillPublicationPolicy(mapperValidator(), mapper);

    private static jakarta.validation.Validator mapperValidator() {
        return VALIDATORS.getValidator();
    }

    @AfterAll
    static void closeValidation() {
        VALIDATORS.close();
    }

    @Test
    void acceptsExplicitDeclarationsAndValidSemverPreRelease() {
        vocabulary();
        assertDoesNotThrow(() -> policy.validateSubmission(SkillTestFixture.metadata()));
        assertDoesNotThrow(() -> policy.validateDraft(metadata("1.2.3-alpha.1+build.42", "MIT", true, true,
                "https://example.com", List.of("coding"), "development")));
    }

    @Test
    void missingLicenseAllowedInDraftButCannotPublishWithoutEveryConfirmation() {
        vocabulary();
        SkillPublicationMetadataDTO missing = metadata("1.0.0", "", false, false, "", List.of(), "development");
        assertDoesNotThrow(() -> policy.validateDraft(missing));
        assertThrows(ClientException.class, () -> policy.validateSubmission(missing));
        for (SkillPublicationMetadataDTO rejected : List.of(
                metadata("1.0.0", "MIT", false, true, "", List.of(), "development"),
                metadata("1.0.0", "MIT", true, false, "", List.of(), "development"),
                metadata("1.0.0", null, true, true, "", List.of(), "development"))) {
            assertThrows(ClientException.class, () -> policy.validateSubmission(rejected));
        }
    }

    @Test
    void rejectsUnknownVocabularyDuplicateTagsInvalidVersionAndCredentialUrls() {
        vocabulary();
        for (String version : List.of("1", "v1.0.0", "01.0.0", "1.0.0-01", "1.0.0-")) {
            assertThrows(ClientException.class, () -> policy.validateDraft(metadata(version, "MIT", true, true,
                    "", List.of(), "development")));
        }
        for (String source : List.of("file:///secret", "javascript:alert(1)", "https://user:secret@example.com", "no space")) {
            assertThrows(ClientException.class, () -> policy.validateDraft(metadata("1.0.0", "MIT", true, true,
                    source, List.of(), "development")));
        }
        assertThrows(ClientException.class, () -> policy.validateDraft(metadata("1.0.0", "MIT", true, true,
                "", List.of("coding", "coding"), "development")));
        assertThrows(ClientException.class, () -> policy.validateDraft(metadata("1.0.0", "MIT", true, true,
                "", List.of("unknown"), "development")));
        assertThrows(ClientException.class, () -> policy.validateDraft(metadata("1.0.0", "MIT", true, true,
                "", List.of(), "unknown")));
        assertThrows(ClientException.class, () -> policy.validateDraft(null));
    }

    private void vocabulary() {
        SkillOptionProjection category = new SkillOptionProjection();
        category.setId("development");
        SkillOptionProjection tag = new SkillOptionProjection();
        tag.setId("coding");
        when(mapper.selectCategories()).thenReturn(List.of(category));
        when(mapper.selectTags()).thenReturn(List.of(tag));
    }

    private SkillPublicationMetadataDTO metadata(String version, String license, boolean rights, boolean declarations,
                                                 String source, List<String> tags, String category) {
        return new SkillPublicationMetadataDTO("名称", "简介", category, tags, version, "首版", license, "本人", source,
                "Agent Skills", "无", rights, declarations);
    }
}
