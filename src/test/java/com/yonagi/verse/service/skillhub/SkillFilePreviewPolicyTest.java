package com.yonagi.verse.service.skillhub;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SkillFilePreviewPolicyTest {
    private final SkillFilePreviewPolicy policy = new SkillFilePreviewPolicy();

    @ParameterizedTest
    @MethodSource("supported")
    void selectsOnlyExplicitlySupportedFormats(String path, String kind, String language) {
        assertEquals(new SkillFilePreviewPolicy.Format(kind, language), policy.format(path));
    }

    static Stream<Arguments> supported() {
        return Stream.of(Arguments.of("docs/a.TXT", "TEXT", null), Arguments.of("a.csv", "TEXT", null),
                Arguments.of("SKILL.md", "MARKDOWN", "markdown"), Arguments.of("a.MARKDOWN", "MARKDOWN", "markdown"),
                Arguments.of("a.c", "CODE", "c"), Arguments.of("a.cpp", "CODE", "cpp"),
                Arguments.of("a.cs", "CODE", "csharp"), Arguments.of("a.java", "CODE", "java"),
                Arguments.of("a.py", "CODE", "python"), Arguments.of("a.go", "CODE", "go"),
                Arguments.of("a.html", "CODE", "xml"), Arguments.of("a.css", "CODE", "css"),
                Arguments.of("a.js", "CODE", "javascript"), Arguments.of("a.tsx", "CODE", "typescript"),
                Arguments.of("a.rs", "CODE", "rust"), Arguments.of("a.yml", "CODE", "yaml"),
                Arguments.of("Makefile", "CODE", "makefile"), Arguments.of("src/GNUmakefile", "CODE", "makefile"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.doc", "a.docx", "a.xls", "a.xlsx", "a.ppt", "a.pptx", "a.pdf", "a.rtf",
            "a.svg", "a.png", "a.jpg", "a.gif", "a.mp3", "a.wav", "a.mp4", "a.zip", "a.bin", "a.unknown",
            "README", "txt", "java", "a.txt.exe"})
    void unknownAndBinaryFormatsCannotBecomePreviewableThroughTextDetection(String path) {
        assertEquals("UNSUPPORTED", policy.format(path).kind());
        assertNull(policy.format(path).language());
    }

    @Test
    void preservesEmptyUnicodeAndWhitespaceButRejectsMalformedEncodingAndBinaryControls() {
        assertEquals("", policy.text(new byte[0]));
        String original = "\uFEFF中文 😀\r\n\t缩进\f";
        assertEquals(original, policy.text(original.getBytes(StandardCharsets.UTF_8)));
        assertNull(policy.text(new byte[]{(byte) 0xC3, (byte) 0x28}));
        assertNull(policy.text(new byte[]{'a', 0, 'b'}));
        assertNull(policy.text(new byte[]{0x7F}));
        assertNull(policy.text(new byte[]{1}));
    }
}
