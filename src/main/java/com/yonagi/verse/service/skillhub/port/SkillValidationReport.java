package com.yonagi.verse.service.skillhub.port;

import java.util.List;
import com.yonagi.verse.dto.skillhub.SkillValidationIssueDTO;

/** 包校验结果，正文和制品不能进入日志。 */
public record SkillValidationReport(String sourceSha256,
                                    boolean success, String code, String reason, String packageName,
                                    String packageDescription, String skillMarkdown, byte[] archive,
                                    String archiveSha256, String contentSha256, long unpackedBytes,
                                    List<SkillValidatedFile> files, List<SkillValidationIssueDTO> issues) {
    public SkillValidationReport(String sourceSha256, boolean success, String code, String reason, String packageName,
                                  String packageDescription, String skillMarkdown, byte[] archive,
                                  String archiveSha256, String contentSha256, long unpackedBytes,
                                  List<SkillValidatedFile> files) {
        this(sourceSha256, success, code, reason, packageName, packageDescription, skillMarkdown, archive,
                archiveSha256, contentSha256, unpackedBytes, files, List.of());
    }
    @Override
    public String toString() {
        return "SkillValidationReport[success=" + success + ", code=" + code + "]";
    }
}
