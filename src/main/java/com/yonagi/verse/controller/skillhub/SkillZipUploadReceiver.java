package com.yonagi.verse.controller.skillhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.service.skillhub.SkillImportService;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Servlet非阻塞接收：不占阻塞读取线程，120秒总期限独立于客户端发包速度。 */
@Component
@Slf4j
public class SkillZipUploadReceiver {
    private static final long TIMEOUT_MILLIS = 120000L;
    private static final int BUFFER_BYTES = 8192;
    private final SkillImportService importService;
    private final ThreadPoolTaskExecutor executor;
    private final ObjectMapper objectMapper;
    private final SkillExceptionAdvice exceptionAdvice;
    private final Semaphore requests = new Semaphore(2);

    /** 注入有界存储工作池；HTTP超时不依赖S3或数据库返回。 */
    public SkillZipUploadReceiver(SkillImportService importService,
                                  @Qualifier("skillUploadExecutor") ThreadPoolTaskExecutor executor,
                                  ObjectMapper objectMapper, SkillExceptionAdvice exceptionAdvice) {
        this.importService = importService;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.exceptionAdvice = exceptionAdvice;
    }

    /**
     * @param userId 请求线程确认的可信账号，异步回调不读ThreadLocal
     * @param jobId 本人任务，存储前仍由Service重查归属
     * @param request 原始ZIP请求
     * @param response 成功202、超时408；所有路径有界结束并释放接收预算
     * @throws IOException 初始化Servlet输入失败
     */
    public void receive(Long userId, Long jobId, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (request.getContentLengthLong() > SkillPackageLimitConstant.MAX_ARCHIVE_BYTES) {
            throw new ClientException(SkillErrorCodeEnum.LIMIT_EXCEEDED);
        }
        if (!request.isAsyncSupported()) {
            throw new ServerException("Skill上传需要Servlet异步输入支持", SkillErrorCodeEnum.UNAVAILABLE);
        }
        if (!requests.tryAcquire()) {
            throw new ClientException(SkillErrorCodeEnum.RATE_LIMITED);
        }
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean readEnded = new AtomicBoolean();
        AsyncContext context;
        ServletInputStream input;
        try {
            input = request.getInputStream();
            context = request.startAsync(request, response);
            context.setTimeout(TIMEOUT_MILLIS);
        } catch (IOException | RuntimeException exception) {
            requests.release();
            throw exception;
        }
        try {
            install(context, response, input, userId, jobId, finished, readEnded);
        } catch (RuntimeException exception) {
            failure(context, response, finished, exception);
        }
    }

    private void install(AsyncContext context, HttpServletResponse response, ServletInputStream input,
                         Long userId, Long jobId, AtomicBoolean finished, AtomicBoolean readEnded) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        context.addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                readEnded.set(true);
                if (finished.compareAndSet(false, true)) {
                    requests.release();
                }
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                readEnded.set(true);
                finish(context, response, finished, 408, Results.failure(SkillErrorCodeEnum.INVALID_INPUT.code(),
                        "ZIP接收超过120秒，请重试原任务"));
            }

            @Override
            public void onError(AsyncEvent event) {
                readEnded.set(true);
                finish(context, response, finished, 400, Results.failure(SkillErrorCodeEnum.INVALID_INPUT.code(),
                        "ZIP传输中断，请重试原任务"));
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                // 本入口不再次dispatch或启动新的异步周期。
            }
        });
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        input.setReadListener(new ReadListener() {
            @Override
            public void onDataAvailable() throws IOException {
                byte[] buffer = new byte[BUFFER_BYTES];
                while (!readEnded.get() && input.isReady() && !input.isFinished()) {
                    if (System.nanoTime() >= deadline) {
                        readEnded.set(true);
                        finish(context, response, finished, 408,
                                Results.failure(SkillErrorCodeEnum.INVALID_INPUT.code(), "ZIP接收超时"));
                        return;
                    }
                    int count = input.read(buffer);
                    if (count < 0) {
                        return;
                    }
                    if ((long) bytes.size() + count > SkillPackageLimitConstant.MAX_ARCHIVE_BYTES) {
                        readEnded.set(true);
                        finish(context, response, finished, 413,
                                Results.failure(SkillErrorCodeEnum.LIMIT_EXCEEDED.code(),
                                SkillErrorCodeEnum.LIMIT_EXCEEDED.message()));
                        return;
                    }
                    bytes.write(buffer, 0, count);
                }
            }

            @Override
            public void onAllDataRead() {
                if (!readEnded.compareAndSet(false, true)) {
                    return;
                }
                if (System.nanoTime() >= deadline) {
                    finish(context, response, finished, 408,
                            Results.failure(SkillErrorCodeEnum.INVALID_INPUT.code(), "ZIP接收超时"));
                    return;
                }
                try {
                    executor.execute(() -> {
                        if (!finished.get()) {
                            try {
                                finish(context, response, finished, 202,
                                        Results.success(importService.upload(userId, jobId, bytes.toByteArray())));
                            } catch (RuntimeException exception) {
                                failure(context, response, finished, exception);
                            }
                        }
                    });
                } catch (RejectedExecutionException exception) {
                    failure(context, response, finished, new ClientException(SkillErrorCodeEnum.RATE_LIMITED));
                }
            }

            @Override
            public void onError(Throwable exception) {
                readEnded.set(true);
                finish(context, response, finished, 400, Results.failure(SkillErrorCodeEnum.INVALID_INPUT.code(),
                        "ZIP传输中断，请重试原任务"));
            }
        });
    }

    private void failure(AsyncContext context, HttpServletResponse response, AtomicBoolean finished,
                         RuntimeException exception) {
        ResponseEntity<Result<Void>> error;
        if (exception instanceof ClientException client) {
            error = exceptionAdvice.client(client);
        } else if (exception instanceof ServerException server) {
            error = exceptionAdvice.server(server);
        } else {
            error = exceptionAdvice.unexpected(exception, response);
        }
        if (error != null) {
            error.getHeaders().forEach((name, values) -> response.setHeader(name, values.getFirst()));
            finish(context, response, finished, error.getStatusCode().value(), error.getBody());
        }
    }

    private void finish(AsyncContext context, HttpServletResponse response, AtomicBoolean finished,
                        int status, Object body) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        try {
            response.setStatus(status);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            objectMapper.writeValue(response.getOutputStream(), body);
        } catch (IOException | IllegalStateException exception) {
            log.debug("Skill上传响应连接已结束: type={}", exception.getClass().getSimpleName());
        } finally {
            requests.release();
            try {
                context.complete();
            } catch (IllegalStateException exception) {
                // 容器可能先于响应写入关闭异步周期，预算已释放，不再修改已结束连接。
                log.debug("Skill上传异步周期已结束");
            }
        }
    }
}
