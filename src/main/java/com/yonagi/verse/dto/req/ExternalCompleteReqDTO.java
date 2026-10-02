package com.yonagi.verse.dto.req;
import lombok.Data;
import jakarta.validation.constraints.*;
@Data
public class ExternalCompleteReqDTO {
    /** 用户选择的本站账号 */
    @Pattern(regexp="[0-9]{1,19}")
    private String userId;
}
