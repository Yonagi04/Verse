package com.yonagi.verse.dto.req;
import lombok.Data;
import jakarta.validation.constraints.*;
@Data
public class ExternalFlowStartReqDTO {
    /** 平台 */
    @NotBlank @Pattern(regexp="google|github|gitlab|feishu")
    private String provider;
    /** 近期验密证明 */
    @Size(max=128)
    private String reauthToken;
}
