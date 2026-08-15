package com.yonagi.verse.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/15 11:02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    private final S3Client s3Client;
    private final UserMapper userMapper;

    @Value("${verse.s3.bucket}")
    private String bucket;

    @Value("${verse.s3.base-url}")
    private String baseUrl;

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp");
    // 限制图片大小为5mb
    private static final long MAX_SIZE = 5 * 1024 * 1024;
    private static final int AVATAR_SIZE = 256;

    public String uploadAvatar(Long userId, MultipartFile file) {
        // 1. 校验
        if (file.isEmpty() || !ALLOWED_TYPES.contains(file.getContentType())) {
            throw new ClientException(UserErrorCodeEnum.AVATAR_TYPE_INVALID);
        }
        if (file.getSize() > MAX_SIZE) {
            throw new ClientException(UserErrorCodeEnum.AVATAR_SIZE_EXCEED);
        }
        // 2. 图片处理：中心裁剪 + 缩放 256x256 + 转 WebP
        byte[] processed;
        try {
            processed = processImage(file);
        } catch (IOException e) {
            log.error("Failed to process avatar image for userId: {}", userId, e);
            throw new ServerException(UserErrorCodeEnum.AVATAR_PROCESS_ERROR);
        }
        // 3. 生成 objectKey
        String objectKey = String.format("avatars/%d/%s.webp", userId, UUID.randomUUID());
        // 4. 上传到 S3
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType("image/webp")
                            .build(),
                    RequestBody.fromBytes(processed));
        } catch (Exception e) {
            log.error("Failed to upload avatar to S3 for userId: {}", userId, e);
            throw new ServerException(UserErrorCodeEnum.AVATAR_UPLOAD_ERROR);
        }
        // 5. 更新 DB
        userMapper.update(null,
                Wrappers.lambdaUpdate(UserDO.class)
                        .eq(UserDO::getUserId, userId)
                        .eq(UserDO::getStatus, 1)
                        .eq(UserDO::getDelFlag, 0)
                        .set(UserDO::getAvatar, objectKey));
        // 6. 返回完整 URL
        return baseUrl + "/" + bucket + "/" + objectKey;
    }

    private byte[] processImage(MultipartFile file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thumbnails.of(file.getInputStream())
                .size(AVATAR_SIZE, AVATAR_SIZE)
                .crop(Positions.CENTER)
                .outputFormat("webp")
                .outputQuality(0.85)
                .toOutputStream(out);
        return out.toByteArray();
    }
}
