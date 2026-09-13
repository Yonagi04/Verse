package com.yonagi.verse.common.web;

import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    @Test
    void shouldReturnFriendlyMessageWhenUploadSizeExceeded() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tenants/1/banner");

        Result<Void> result = exceptionHandler.handleMaxUploadSizeExceededException(
                request, new MaxUploadSizeExceededException(5L * 1024 * 1024));

        assertEquals(BaseErrorCode.UPLOAD_FILE_SIZE_EXCEED.code(), result.getCode());
        assertEquals(BaseErrorCode.UPLOAD_FILE_SIZE_EXCEED.message(), result.getMessage());
    }
}
