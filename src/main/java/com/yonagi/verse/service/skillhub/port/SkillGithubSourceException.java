package com.yonagi.verse.service.skillhub.port;

import com.yonagi.verse.dto.skillhub.SkillValidationIssueDTO;
import java.time.Instant;
import java.util.List;

/** 外部错误归一化；不附带HTTP原文或可能包含秘密的cause。 */
public class SkillGithubSourceException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final Instant retryAt;
    private final List<SkillValidationIssueDTO> issues;

    public SkillGithubSourceException(String code, String message, boolean retryable, Instant retryAt) {
        this(code, message, retryable, retryAt, List.of());
    }

    public SkillGithubSourceException(String code, String message, boolean retryable, Instant retryAt,
                                      List<SkillValidationIssueDTO> issues) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.retryAt = retryAt;
        this.issues = List.copyOf(issues);
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public Instant retryAt() { return retryAt; }
    public List<SkillValidationIssueDTO> issues() { return issues; }
}
