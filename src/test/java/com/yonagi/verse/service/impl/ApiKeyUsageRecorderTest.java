package com.yonagi.verse.service.impl;

import com.yonagi.verse.dao.mapper.ApiKeyMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ApiKeyUsageRecorderTest {

    @Test
    void databaseWriteRunsOnlyWhenScheduledTaskExecutes() {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        ApiKeyUsageRecorder recorder = new ApiKeyUsageRecorder(mapper, executor);
        Date usedAt = new Date();

        recorder.record(30L, usedAt);
        verifyNoInteractions(mapper);
        org.mockito.ArgumentCaptor<Runnable> task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());

        task.getValue().run();
        verify(mapper).updateLastUsedAtIfLater(30L, usedAt);
    }

    @Test
    void writeFailureDoesNotBreakAuthentication() {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        TaskExecutor executor = Runnable::run;
        doThrow(new IllegalStateException("database unavailable"))
                .when(mapper).updateLastUsedAtIfLater(eq(30L), any(Date.class));

        assertDoesNotThrow(() -> new ApiKeyUsageRecorder(mapper, executor).record(30L, new Date()));
    }
}
