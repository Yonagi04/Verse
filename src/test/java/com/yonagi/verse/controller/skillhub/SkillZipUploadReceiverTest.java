package com.yonagi.verse.controller.skillhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dto.resp.SkillImportTaskRespDTO;
import com.yonagi.verse.service.skillhub.SkillImportService;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证HTTP接收状态与预算恢复，使用可控Servlet事件，不连接S3或等待真实两分钟。 */
class SkillZipUploadReceiverTest {
    private final SkillImportService imports = mock(SkillImportService.class);
    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    private final SkillZipUploadReceiver receiver = new SkillZipUploadReceiver(imports, executor,
            new ObjectMapper().findAndRegisterModules(), new SkillExceptionAdvice());

    @BeforeEach
    void inlineStorageBoundary() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
    }

    @Test
    void stalledRequestsTimeoutAndReleaseExactlyOneSlot() throws Exception {
        Request first = new Request(new byte[0], false, false);
        Request second = new Request(new byte[0], false, false);
        receiver.receive(1L, 50L, first, new MockHttpServletResponse());
        receiver.receive(1L, 51L, second, new MockHttpServletResponse());
        assertEquals(120000L, first.getAsyncContext().getTimeout());
        Request replacement = new Request(new byte[0], false, false);
        assertThrows(ClientException.class,
                () -> receiver.receive(1L, 52L, replacement, new MockHttpServletResponse()));
        MockAsyncContext async = (MockAsyncContext) first.getAsyncContext();
        MockHttpServletResponse response = (MockHttpServletResponse) async.getResponse();
        timeout(async);
        timeout(async);
        assertEquals(408, response.getStatus());
        assertDoesNotThrow(() -> receiver.receive(1L, 52L, replacement, new MockHttpServletResponse()));
        assertThrows(ClientException.class, () -> receiver.receive(1L, 53L,
                new Request(new byte[0], false, false), new MockHttpServletResponse()));
        verifyNoInteractions(imports);
    }

    @Test
    void completedBodyReturnsAcceptedQueuedStateAndPreservesBytes() throws Exception {
        byte[] archive = new byte[]{1, 2, 3};
        when(imports.upload(eq(1L), eq(50L), any())).thenReturn(new SkillImportTaskRespDTO("50", "30", "QUEUED",
                3L, null, null, 0, OffsetDateTime.now()));
        for (int index = 0; index < 3; index++) {
            Request request = new Request(archive, true, false);
            MockHttpServletResponse response = new MockHttpServletResponse();
            receiver.receive(1L, 50L, request, response);
            request.input.listener.onDataAvailable();
            request.input.listener.onAllDataRead();
            assertEquals(202, response.getStatus());
            assertEquals("QUEUED", new ObjectMapper().readTree(response.getContentAsByteArray())
                    .path("data").path("status").textValue());
            assertEquals("application/json;charset=UTF-8", response.getContentType());
        }
        org.mockito.ArgumentCaptor<byte[]> captured = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(imports, org.mockito.Mockito.times(3)).upload(eq(1L), eq(50L), captured.capture());
        assertArrayEquals(archive, captured.getValue());
    }

    @Test
    void actualBodyLimitRejectsBeforeStorageEvenWithoutContentLength() throws Exception {
        Request request = new Request(new byte[20 * 1024 * 1024 + 1], true, false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        receiver.receive(1L, 50L, request, response);
        request.input.listener.onDataAvailable();
        assertEquals(413, response.getStatus());
        verifyNoInteractions(imports);
    }

    @Test
    void listenerInstallationFailureCompletesResponseAndDoesNotLeakReceiveBudget() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertDoesNotThrow(() -> receiver.receive(1L, 50L, new Request(new byte[0], false, true), response));
        assertEquals(503, response.getStatus());
        assertDoesNotThrow(() -> receiver.receive(1L, 51L, new Request(new byte[0], false, false),
                new MockHttpServletResponse()));
        assertDoesNotThrow(() -> receiver.receive(1L, 52L, new Request(new byte[0], false, false),
                new MockHttpServletResponse()));
    }

    private void timeout(MockAsyncContext context) throws IOException {
        for (AsyncListener listener : context.getListeners()) {
            listener.onTimeout(new AsyncEvent(context));
        }
    }

    private static final class Request extends MockHttpServletRequest {
        private final Input input;

        private Request(byte[] bytes, boolean ready, boolean rejectListener) {
            input = new Input(bytes, ready, rejectListener);
            setAsyncSupported(true);
        }

        @Override
        public ServletInputStream getInputStream() {
            return input;
        }
    }

    private static final class Input extends ServletInputStream {
        private final ByteArrayInputStream bytes;
        private final boolean ready;
        private final boolean rejectListener;
        private ReadListener listener;

        private Input(byte[] content, boolean ready, boolean rejectListener) {
            bytes = new ByteArrayInputStream(content);
            this.ready = ready;
            this.rejectListener = rejectListener;
        }

        @Override
        public boolean isFinished() {
            return bytes.available() == 0;
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public void setReadListener(ReadListener value) {
            if (rejectListener) {
                throw new IllegalStateException("listener setup rejected");
            }
            listener = value;
        }

        @Override
        public int read() {
            return bytes.read();
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            return bytes.read(target, offset, length);
        }
    }
}
