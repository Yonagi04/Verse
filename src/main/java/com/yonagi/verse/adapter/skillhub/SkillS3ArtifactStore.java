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
