package com.yonagi.verse.dto.req;
import lombok.Data;
import jakarta.validation.constraints.*;
@Data
public class ExternalUnbindReqDTO {
    /** 近期验密证明 */
    @NotBlank @Size(max=128)
    private String reauthToken;
    /** 本次操作幂等标识 */
    @NotBlank @Pattern(regexp="[a-zA-Z0-9-]{16,64}")
    private String operationId;
}
