package com.yonagi.verse.common.web;

import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.convention.result.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private static final String QUERY_VALUE = "private-query-fixture";
    private static final String PATH_VALUE = "private-path-fixture";
    private static final String EXCEPTION_DETAIL = "private-exception-fixture";

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    @Test
    void shouldReturnFriendlyMessageWhenUploadSizeExceeded(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tenants/" + PATH_VALUE + "/banner");
        request.setQueryString("token=" + QUERY_VALUE);

        Result<Void> result = exceptionHandler.handleMaxUploadSizeExceededException(
                request, new MaxUploadSizeExceededException(5L * 1024 * 1024));

        assertEquals(BaseErrorCode.UPLOAD_FILE_SIZE_EXCEED.code(), result.getCode());
        assertEquals(BaseErrorCode.UPLOAD_FILE_SIZE_EXCEED.message(), result.getMessage());
        assertThat(output.getAll()).contains("maxUploadSize=5242880").doesNotContain(PATH_VALUE, QUERY_VALUE);
    }

    @ParameterizedTest
    @CsvSource({
            "unexpected, IllegalStateException",
            "business-cause, IllegalArgumentException",
            "business, ClientException",
            "authentication, BadCredentialsException",
            "denied, 权限不足",
            "mismatch, limit"
    })
    void mvcErrorsLogRouteTemplateAndSafeDiagnostics(String kind, String diagnostic, CapturedOutput output)
            throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ErrorEndpoint())
                .setControllerAdvice(exceptionHandler)
                .build();
        BaseErrorCode expectedCode = switch (kind) {
            case "authentication", "denied" -> BaseErrorCode.TOKEN_INVALID;
            case "business", "mismatch" -> BaseErrorCode.CLIENT_ERROR;
            default -> BaseErrorCode.SERVICE_ERROR;
        };

        mvc.perform(get("/errors/{kind}/{value}?token={token}&limit={limit}", kind, PATH_VALUE, QUERY_VALUE,
                        "mismatch".equals(kind) ? QUERY_VALUE : "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(expectedCode.code()));

        assertThat(output.getAll())
                .doesNotContain(QUERY_VALUE, PATH_VALUE, EXCEPTION_DETAIL)
                .contains("/errors/{kind}/{value}", diagnostic);
    }

    @RestController
    static class ErrorEndpoint {

        @GetMapping("/errors/{kind}/{value}")
        public void fail(@PathVariable String kind, @RequestParam Integer limit) {
            switch (kind) {
                case "authentication" -> throw new BadCredentialsException(EXCEPTION_DETAIL);
                case "denied" -> throw new AccessDeniedException(EXCEPTION_DETAIL);
                case "business" -> throw new ClientException(EXCEPTION_DETAIL, BaseErrorCode.CLIENT_ERROR);
                case "business-cause" -> throw new ServerException(EXCEPTION_DETAIL,
                        new IllegalArgumentException(QUERY_VALUE), BaseErrorCode.SERVICE_ERROR);
                default -> throw new IllegalStateException(EXCEPTION_DETAIL, new IllegalArgumentException(QUERY_VALUE));
            }
        }
    }
}
