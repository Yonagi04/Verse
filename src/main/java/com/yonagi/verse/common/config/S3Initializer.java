package com.yonagi.verse.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/15 10:20
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class S3Initializer implements ApplicationRunner {

    private final S3Client s3Client;

    @Value("${verse.s3.bucket}")
    private String bucket;

    @Value("${verse.s3.create-bucket-if-not-exists:true}")
    private boolean createBucketIfNotExists;

    @Override
    public void run(ApplicationArguments args) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("S3 bucket exists: {}", bucket);
        } catch (NoSuchBucketException e) {
            if (createBucketIfNotExists) {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Created S3 bucket: {}", bucket);
            } else {
                log.warn("S3 bucket '{}' does not exist and auto-create is disabled. Please create it manually.", bucket);
            }
        } catch (Exception e) {
            log.error("Failed to init S3 bucket: {}", bucket, e);
        }
    }
}
