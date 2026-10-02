package com.yonagi.verse.dto.req;
import lombok.Data;
import jakarta.validation.constraints.*;
@Data
public class ExternalAttachReqDTO {
    /** 当前账号近期验密证明 */
    @NotBlank @Size(max=128)
    private String reauthToken;
}
