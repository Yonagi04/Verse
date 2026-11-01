package com.yonagi.verse.adapter.skillhub;

import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.service.skillhub.port.SkillArtifactStore;
import com.yonagi.verse.service.skillhub.port.SkillObjectPage;
import com.yonagi.verse.service.skillhub.port.SkillStoredObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import com.yonagi.verse.common.constant.SkillGithubLimitConstant;
import com.yonagi.verse.service.skillhub.port.SkillImportBudget;

/** Skill文件的有界S3读写；前缀私有策略由部署维护，不依赖桶管理权限。 */
@Component
@Slf4j
public class SkillS3ArtifactStore implements SkillArtifactStore {
    private static final int MAX_KEY_LENGTH = 255;
    private static final String PREFIX = "skillhub/v1/";
    private static final int LIST_SIZE = 500;
    private static final int READ_BUFFER_BYTES = 8192;
    private static final int MAX_DIAGNOSTIC_LENGTH = 96;
    private static final Pattern UNSAFE_DIAGNOSTIC_CHARACTER = Pattern.compile("[^A-Za-z0-9_.:-]");
    private static final AwsRequestOverrideConfiguration TIMEOUT = AwsRequestOverrideConfiguration.builder()
            .apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10)).build();
    private final S3Client client;
    private final String bucket;

    /**
     * @param client 既有S3连接
     * @param bucket 既有共享桶，其他前缀可保留公开策略
     */
    public SkillS3ArtifactStore(S3Client client, @Value("${verse.s3.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] bytes) {
        validateKey(key);
        if (bytes == null || bytes.length == 0 || bytes.length > SkillPackageLimitConstant.MAX_ARCHIVE_BYTES) {
            throw unavailable("Skill文件大小不符合存储限制");
        }
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/zip")
                    .cacheControl("no-store").ifNoneMatch("*").overrideConfiguration(TIMEOUT).build(),
                    RequestBody.fromBytes(bytes));
        } catch (SdkException exception) {
            throw storageFailure("PutObject", "Skill文件上传失败，请检查对象存储状态", exception);
        }
    }

    @Override
    public byte[] get(String key, int maximumBytes) {
        validateKey(key);
        if (maximumBytes <= 0 || maximumBytes > SkillPackageLimitConstant.MAX_ARCHIVE_BYTES) {
            throw unavailable("Skill文件读取上限不合法");
        }
        try {
            // 不返回unmanaged响应流；有界读取保持在SDK的整次API超时范围内。
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key)
                    .overrideConfiguration(TIMEOUT).build(), (response, input) -> {
                if (response.contentLength() == null || response.contentLength() > maximumBytes) {
                    input.abort();
                    throw unavailable("Skill文件大小不符合读取限制");
                }
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[READ_BUFFER_BYTES];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || (long) output.size() + count > maximumBytes) {
                        input.abort();
                        throw unavailable("Skill文件读取已取消或超过大小限制");
                    }
                    output.write(buffer, 0, count);
                }
                byte[] bytes = output.toByteArray();
                if (bytes.length != response.contentLength()) {
                    input.abort();
                    throw unavailable("Skill文件读取不完整");
                }
                return bytes;
            });
        } catch (SdkException exception) {
            throw storageFailure("GetObject", "Skill文件读取失败，请检查对象存储状态", exception);
        }
    }

    @Override
    public void delete(String key) {
        validateKey(key);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key)
                    .overrideConfiguration(TIMEOUT).build());
        } catch (SdkException exception) {
            throw storageFailure("DeleteObject", "Skill文件删除失败，请检查对象存储状态", exception);
        }
    }

    @Override
    public SkillObjectPage list(String cursor) {
        try {
            ListObjectsV2Response page = client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket)
                    .prefix(PREFIX).maxKeys(LIST_SIZE).continuationToken(cursor)
                    .overrideConfiguration(TIMEOUT).build());
            List<SkillStoredObject> objects = page.contents().stream()
                    .map(object -> new SkillStoredObject(object.key(), object.lastModified())).toList();
            return new SkillObjectPage(objects, page.nextContinuationToken());
        } catch (SdkException exception) {
            throw storageFailure("ListObjectsV2", "Skill文件列举失败，请检查对象存储状态", exception);
        }
    }

    @Override
    public void putSource(String key, Path path, String contentType, long maximumBytes, SkillImportBudget budget) {
        validateSource(key, maximumBytes);
        budget.check();
        try {
            long length = Files.size(path);
            if (length <= 0 || length > maximumBytes || !("application/zip".equals(contentType)
                    || "application/json".equals(contentType))) {
                throw unavailable("Skill源对象超过限制");
            }
            Duration remaining = budget.remaining();
            AwsRequestOverrideConfiguration timeout = AwsRequestOverrideConfiguration.builder()
                    .apiCallTimeout(remaining).apiCallAttemptTimeout(remaining).build();
            // SDK签名和重试可能多次打开请求体，不能复用不可reset的单个流。
            try (SourceContentProvider provider = new SourceContentProvider(path, budget)) {
                client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                        .cacheControl("no-store").ifNoneMatch("*").overrideConfiguration(timeout).build(),
                        RequestBody.fromContentProvider(provider, length, contentType));
            }
            budget.check();
        } catch (IOException exception) {
            throw unavailable("Skill源对象临时文件不可用");
        } catch (SdkException exception) {
            throw storageFailure("PutSource", "Skill源对象保存失败", exception);
        }
    }

    @Override
    public void downloadSource(String key, Path target, long maximumBytes, SkillImportBudget budget) {
        validateSource(key, maximumBytes);
        budget.check();
        Duration remaining = budget.remaining();
        AwsRequestOverrideConfiguration timeout = AwsRequestOverrideConfiguration.builder()
                .apiCallTimeout(remaining).apiCallAttemptTimeout(remaining).build();
        try {
            client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).overrideConfiguration(timeout).build(),
                    (response, input) -> {
                        if (response.contentLength() == null || response.contentLength() <= 0
                                || response.contentLength() > maximumBytes) {
                            input.abort();
                            throw unavailable("Skill源对象大小不符合限制");
                        }
                        long total = 0;
                        try (OutputStream output = Files.newOutputStream(target)) {
                            byte[] buffer = new byte[READ_BUFFER_BYTES];
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                budget.check();
                                total += count;
                                if (total > maximumBytes) {
                                    input.abort();
                                    throw unavailable("Skill源对象读取超过限制");
                                }
                                output.write(buffer, 0, count);
                            }
                        }
                        budget.check();
                        if (total != response.contentLength()) {
                            input.abort();
                            throw unavailable("Skill源对象读取不完整");
                        }
                        return null;
                    });
        } catch (SdkException exception) {
            throw storageFailure("DownloadSource", "Skill源对象读取失败", exception);
        }
    }

    private void validateSource(String key, long maximumBytes) {
        validateKey(key);
        if (!key.startsWith("skillhub/v1/source/") || maximumBytes <= 0
                || maximumBytes > SkillGithubLimitConstant.MAX_ARCHIVE_BYTES) {
            throw unavailable("Skill源对象边界不合法");
        }
    }

    private final class SourceContentProvider implements software.amazon.awssdk.http.ContentStreamProvider, AutoCloseable {
        private final Path path;
        private final SkillImportBudget budget;
        private InputStream current;

        private SourceContentProvider(Path path, SkillImportBudget budget) {
            this.path = path;
            this.budget = budget;
        }

        @Override
        public InputStream newStream() {
            budget.check();
            try {
                close();
                current = new java.io.FilterInputStream(Files.newInputStream(path)) {
                    @Override
                    public int read(byte[] data, int offset, int size) throws IOException {
                        budget.check();
                        return super.read(data, offset, size);
                    }

                    @Override
                    public int read() throws IOException {
                        budget.check();
                        return super.read();
                    }
                };
                return current;
            } catch (IOException exception) {
                throw unavailable("Skill源对象临时文件不可读取");
            }
        }

        @Override
        public void close() throws IOException {
            if (current != null) {
                current.close();
                current = null;
            }
        }
    }

    private ServerException storageFailure(String operation, String message, SdkException exception) {
        Integer status = null;
        String code = null;
        String requestId = null;
        if (exception instanceof S3Exception s3Exception) {
            status = s3Exception.statusCode();
            code = s3Exception.awsErrorDetails() == null ? null : s3Exception.awsErrorDetails().errorCode();
            requestId = s3Exception.requestId();
        }
        // 原始SDK文本可能携带响应体，不能经全局异常处理器的cause日志绕过脱敏。
        log.warn("Skill存储调用失败: operation={}, status={}, code={}, requestId={}, type={}",
                operation, status, safeDiagnostic(code), safeDiagnostic(requestId),
                exception.getClass().getSimpleName());
        return unavailable(message);
    }

    private String safeDiagnostic(String value) {
        if (value == null) {
            return "-";
        }
        String bounded = value.substring(0, Math.min(value.length(), MAX_DIAGNOSTIC_LENGTH));
        return UNSAFE_DIAGNOSTIC_CHARACTER.matcher(bounded).replaceAll("_");
    }

    private void validateKey(String key) {
        if (key == null || !key.startsWith(PREFIX) || key.contains("..") || key.length() > MAX_KEY_LENGTH) {
            throw unavailable("Skill存储对象路径不合法");
        }
    }

    private ServerException unavailable(String message) {
        return new ServerException(message, SkillErrorCodeEnum.UNAVAILABLE);
    }
}
