package com.yonagi.verse.common.web;

import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实 MVC resolver 链验证异常契约，不以直接调用 handler 代替框架行为。 */
class FatalErrorMvcContractTest {
    private AnnotationConfigWebApplicationContext context;
    private Endpoint endpoint;
    private MockMvc mvc;

    @BeforeEach
    void mvcContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(MvcConfiguration.class);
        context.refresh();
        endpoint = context.getBean(Endpoint.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"direct", "wrapped", "unreadable", "business"})
    void fatalErrorCannotBecomeAResultOrClientError(String path) {
        OutOfMemoryError fatal = new OutOfMemoryError("fixture fatal");
        endpoint.fatal = fatal;
        assertSame(fatal, assertThrows(ServletException.class,
                () -> mvc.perform(get("/errors/" + path))).getCause());
    }

    @Test
    void linkageAndThreadTerminationAlsoEscapeMvcResolvers() {
        NoClassDefFoundError linkage = new NoClassDefFoundError("fixture linkage");
        endpoint.fatal = linkage;
        assertSame(linkage, assertThrows(ServletException.class,
                () -> mvc.perform(get("/errors/wrapped"))).getCause());
        ThreadDeath terminated = new ThreadDeath();
        endpoint.fatal = terminated;
        assertSame(terminated, assertThrows(ServletException.class,
                () -> mvc.perform(get("/errors/unreadable"))).getCause());
    }

    @Test
    void normalExceptionsKeepExistingPublicFailureContract() throws Exception {
        mvc.perform(get("/errors/ordinary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseErrorCode.SERVICE_ERROR.code()));
        mvc.perform(get("/errors/invalid-json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseErrorCode.CLIENT_ERROR.code()));
    }

    @Configuration
    @EnableWebMvc
    static class MvcConfiguration {
        @Bean
        Endpoint endpoint() {
            return new Endpoint();
        }

        @Bean
        GlobalExceptionHandler errors() {
            return new GlobalExceptionHandler();
        }

        @Bean
        FatalErrorHandlerExceptionResolver fatalErrors() {
            return new FatalErrorHandlerExceptionResolver();
        }
    }

    @RestController
    static class Endpoint {
        private Error fatal;

        @GetMapping("/errors/{kind}")
        public Object fail(@PathVariable String kind, HttpServletRequest request) {
            switch (kind) {
                case "direct" -> throw fatal;
                case "wrapped" -> throw new IllegalStateException("wrapper", fatal);
                case "unreadable" -> throw new HttpMessageNotReadableException(
                        "invalid request", fatal, new ServletServerHttpRequest(request));
                case "business" -> throw new ClientException("business", fatal, BaseErrorCode.CLIENT_ERROR);
                case "invalid-json" -> throw new HttpMessageNotReadableException(
                        "invalid request", new ServletServerHttpRequest(request));
                default -> throw new IllegalStateException("ordinary failure");
            }
        }
    }
}
