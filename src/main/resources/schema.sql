-- 创建数据库
CREATE DATABASE IF NOT EXISTS verse DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE verse;

-- ============================================
-- 1. 用户表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_user` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`               BIGINT       NOT NULL COMMENT '用户唯一标识（业务ID）',
    `username`              VARCHAR(50)  NOT NULL COMMENT '登录用户名，注册之后就不可修改，只能是字母、数字的组合',
    `nickname`              VARCHAR(50)  NOT NULL COMMENT '昵称，类似于姓名，可以修改，可以是汉字，字母，数字和符号',
    `password`              VARCHAR(255) NOT NULL COMMENT '密码（BCrypt加密）',
    `email`                 VARCHAR(256) NOT NULL COMMENT '邮箱（AES-256-GCM加密）',
    `email_hash`            VARCHAR(128)  NOT NULL COMMENT '邮箱哈希（SHA-256，用于查询）',
    `phone`                 VARCHAR(256) DEFAULT NULL COMMENT '手机号（AES-256-GCM加密）',
    `phone_hash`            VARCHAR(128)  DEFAULT NULL COMMENT '手机号哈希（SHA-256，用于查询）',
    `status`                TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0=禁用, 1=正常',
    `last_active_tenant_id` BIGINT       DEFAULT NULL COMMENT '当前活跃租户ID',
    `avatar`    VARCHAR(512) DEFAULT NULL COMMENT '头像在 S3 中的 objectKey',
    `bio`       VARCHAR(255) DEFAULT NULL COMMENT '个人简介',
    `region`    VARCHAR(50)  DEFAULT NULL COMMENT '地区',
    `timezone`  VARCHAR(50)  DEFAULT NULL COMMENT '时区',
    `create_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`              TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=未删除, 1=已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_phone_hash` (`phone_hash`),
    KEY `idx_email_hash` (`email_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ============================================
-- 2. 租户表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_tenant` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`   BIGINT       NOT NULL COMMENT '租户唯一标识（业务ID）',
    `name`        VARCHAR(100) NOT NULL COMMENT '租户名称',
    `type`        VARCHAR(20)  NOT NULL COMMENT '类型：PERSONAL / TEAM',
    `owner_id`    BIGINT       NOT NULL COMMENT '创建者用户ID',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '租户描述',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0=停用, 1=正常',
    `join_approval_mode` TINYINT NOT NULL DEFAULT 0 COMMENT '加入审批模式：0=直接加入, 1=管理员审批；TODO 2=多级审批',
    `rate_limit_rpm`   INT      DEFAULT NULL COMMENT '租户级 RPM 上限（NULL=不限）',
    `rate_limit_tpm`   INT      DEFAULT NULL COMMENT '租户级 TPM 上限（NULL=不限）',
    `audit_enabled`    TINYINT  NOT NULL DEFAULT 0 COMMENT '是否开启模型调用审计：0=关闭, 1=开启',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_id` (`tenant_id`),
    KEY `idx_owner_id` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户表';

-- ============================================
-- 3. 用户-租户关联表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_user_tenant` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`          BIGINT      NOT NULL COMMENT '用户ID（业务ID）',
    `tenant_id`        BIGINT      NOT NULL COMMENT '租户ID（业务ID）',
    `role`             VARCHAR(20) NOT NULL DEFAULT 'MEMBER' COMMENT '角色：SUPER_ADMIN / ADMIN / MEMBER',
    `joined_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    `last_accessed_at` DATETIME    DEFAULT NULL COMMENT '最近一次切换至该租户的时间',
    `left_at`          DATETIME    DEFAULT NULL COMMENT '离开时间（NULL=仍在租户内）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_tenant` (`user_id`, `tenant_id`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户-租户关联表';

-- ============================================
-- 4. 租户邀请表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_tenant_invite` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`   BIGINT      NOT NULL COMMENT '租户ID（业务ID）',
    `code`        VARCHAR(32) NOT NULL COMMENT '邀请码（UUID前8位大写）',
    `created_by`  BIGINT      NOT NULL COMMENT '创建者用户ID',
    `expires_at`  DATETIME    DEFAULT NULL COMMENT '过期时间',
    `is_active`   TINYINT     NOT NULL DEFAULT 1 COMMENT '是否有效：0=已失效, 1=有效',
    `usage_count` INT         NOT NULL DEFAULT 0 COMMENT '通过此邀请码/链接加入的人数',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户邀请表';

-- ============================================
-- 5. API Key 表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_api_key` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `api_key_id`    BIGINT       NOT NULL COMMENT 'API Key唯一标识（业务ID）',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID（业务ID）',
    `tenant_id`     BIGINT       NOT NULL COMMENT '租户ID（业务ID）',
    `api_key`       VARCHAR(64)  NOT NULL COMMENT 'API Key（SHA-256哈希存储）',
    `key_prefix`    VARCHAR(16)   NOT NULL COMMENT 'API Key前缀（明文，用于识别）',
    `name`          VARCHAR(50)  DEFAULT NULL COMMENT 'Key的备注名',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0=已吊销, 1=正常',
    `last_used_at`  DATETIME     DEFAULT NULL COMMENT '最近使用时间',
    `expires_at`    DATETIME     DEFAULT NULL COMMENT '过期时间（NULL=永不过期）',
    `rate_limit_rpm` INT         DEFAULT NULL COMMENT 'Key 级 RPM 上限（NULL=不限）',
    `rate_limit_tpm` INT         DEFAULT NULL COMMENT 'Key 级 TPM 上限（NULL=不限）',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_api_key` (`api_key`),
    UNIQUE KEY `uk_api_key_id` (`api_key_id`),
    KEY `idx_user_tenant` (`user_id`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API Key表';

-- ============================================
-- 6. LLM 服务配置表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_llm_service` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `service_id`  BIGINT       NOT NULL COMMENT '服务唯一标识（业务ID）',
    `tenant_id`   BIGINT       NOT NULL COMMENT '租户ID（业务ID）',
    `name`        VARCHAR(50)  NOT NULL COMMENT '服务别名',
    `provider`    VARCHAR(50)  NOT NULL COMMENT '提供商（如openai, anthropic）',
    `api_url`     VARCHAR(255) NOT NULL COMMENT 'API地址',
    `api_key`     VARCHAR(512) NOT NULL COMMENT '真实的LLM API Key（AES加密存储）',
    `model_name`  VARCHAR(100) DEFAULT NULL COMMENT '默认模型名',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0=禁用, 1=启用',
    `created_by`  BIGINT       NOT NULL COMMENT '创建者用户ID',
    `rate_limit_rpm` INT       DEFAULT NULL COMMENT '模型级 RPM 上限（NULL=不限）',
    `rate_limit_tpm` INT       DEFAULT NULL COMMENT '模型级 TPM 上限（NULL=不限）',
    `fallback_service_id` BIGINT DEFAULT NULL COMMENT '备用模型 serviceId（单级降级，NULL=无降级）',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_service_id` (`service_id`),
    UNIQUE KEY `uk_tenant_name_del` (`tenant_id`, `name`, `del_flag`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM服务配置表';

-- ============================================
-- 7. Token 消耗记录表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_token_usage` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`           BIGINT       NOT NULL COMMENT '用户ID（业务ID）',
    `tenant_id`         BIGINT       NOT NULL COMMENT '租户ID（业务ID）',
    `api_key_id`        BIGINT       NOT NULL COMMENT 'API Key ID（业务ID）',
    `service_id`        BIGINT       NOT NULL COMMENT 'LLM服务ID（业务ID）',
    `model`             VARCHAR(100) NOT NULL COMMENT '实际调用的模型名',
    `prompt_tokens`     INT          NOT NULL DEFAULT 0 COMMENT '输入Token数',
    `completion_tokens` INT          NOT NULL DEFAULT 0 COMMENT '输出Token数',
    `total_tokens`      INT          NOT NULL DEFAULT 0 COMMENT '总Token数',
    `request_id`        VARCHAR(64)  DEFAULT NULL COMMENT '请求追踪ID',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_tenant_time` (`user_id`, `tenant_id`, `create_time`),
    KEY `idx_tenant_time` (`tenant_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Token消耗记录表';

-- ============================================
-- 8. 通知记录表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_notification` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `notification_id` BIGINT       NOT NULL COMMENT '通知业务ID（雪花）',
    `tenant_id`       BIGINT       NOT NULL COMMENT '产生通知的租户ID',
    `type`            VARCHAR(30)  NOT NULL COMMENT '通知类型：SYSTEM / ANNOUNCEMENT',
    `severity`        VARCHAR(10)  NOT NULL COMMENT '严重程度：INFO / WARNING / CRITICAL',
    `title`           VARCHAR(200) NOT NULL COMMENT '通知标题',
    `content`         TEXT         NOT NULL COMMENT '通知正文',
    `sender_id`       BIGINT       DEFAULT NULL COMMENT '发送者用户ID（系统通知为NULL）',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0=正常, 1=已删除）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_notification_id` (`notification_id`),
    KEY `idx_tenant_time` (`tenant_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知表';

-- ============================================
-- 9. 通知接收人表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_notification_recipient` (
    `id`              BIGINT    NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`         BIGINT    NOT NULL COMMENT '接收用户ID',
    `notification_id` BIGINT    NOT NULL COMMENT '通知业务ID',
    `is_read`         TINYINT   NOT NULL DEFAULT 0 COMMENT '已读状态：0=未读, 1=已读',
    `read_time`       DATETIME  DEFAULT NULL COMMENT '读取时间',
    `create_time`     DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_notification` (`user_id`, `notification_id`),
    KEY `idx_user_read_time` (`user_id`, `is_read`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知接收人表';
-- ============================================
-- TODO: 定时任务清理超过3个月的通知记录
-- ============================================

-- ============================================
-- 10. 加入租户审批表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_tenant_join_request` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `request_id`       BIGINT       NOT NULL COMMENT '申请唯一标识（雪花 ID）',
    `tenant_id`        BIGINT       NOT NULL COMMENT '目标租户 ID（业务 ID）',
    `user_id`          BIGINT       NOT NULL COMMENT '申请人用户 ID（业务 ID）',
    `invite_id`        BIGINT       NOT NULL COMMENT 'FK → t_tenant_invite.id',
    `status`           VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING / APPROVED / REJECTED',
    `reviewed_by`      BIGINT       DEFAULT NULL COMMENT '审批人用户 ID',
    `review_comment`   VARCHAR(255) DEFAULT NULL COMMENT '审批备注',
    `requested_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    `reviewed_at`      DATETIME     DEFAULT NULL COMMENT '审批时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_request_id` (`request_id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户加入审批表';

-- ============================================
-- 11. 登录设备表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_login_device` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `device_id`       VARCHAR(64)  NOT NULL COMMENT '设备唯一标识（SHA-256）',
    `user_id`         BIGINT       NOT NULL COMMENT '用户ID',
    `device_name`     VARCHAR(128) NOT NULL COMMENT '设备名称（如 Windows Chrome）',
    `ip`              VARCHAR(45)  NOT NULL COMMENT '登录IP地址',
    `region`          VARCHAR(64)  DEFAULT NULL COMMENT 'IP所属地理区域',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '0=已下线, 1=在线',
    `first_login_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次登录时间',
    `last_login_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近登录时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_device` (`user_id`, `device_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录设备表';

-- ============================================
-- 12. 登录历史表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_login_history` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`      BIGINT       NOT NULL COMMENT '用户ID',
    `event_id`     VARCHAR(64)  DEFAULT NULL COMMENT '事件唯一ID（幂等键）',
    `login_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
    `device_name`  VARCHAR(128) NOT NULL COMMENT '设备名称',
    `ip`           VARCHAR(45)  NOT NULL COMMENT '登录IP地址',
    `region`       VARCHAR(64)  DEFAULT NULL COMMENT 'IP所属地理区域',
    `result`       VARCHAR(10)  NOT NULL COMMENT '登录结果：SUCCESS / FAIL',
    `fail_reason`  VARCHAR(128) DEFAULT NULL COMMENT '失败原因',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_id` (`event_id`),
    KEY `idx_user_time` (`user_id`, `login_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录历史表';

-- ============================================
-- 13. 隐私设置表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_user_privacy` (
    `id`            BIGINT  NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`       BIGINT  NOT NULL COMMENT '用户ID',
    `show_bio`      TINYINT NOT NULL DEFAULT 1 COMMENT '公开展示简介：0=否, 1=是',
    `show_region`   TINYINT NOT NULL DEFAULT 1 COMMENT '公开展示地区：0=否, 1=是',
    `show_timezone` TINYINT NOT NULL DEFAULT 1 COMMENT '公开展示时区：0=否, 1=是',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户隐私设置表';

-- ============================================
-- 14. 邀请码计数事件去重表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_counter_event` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `event_id`    VARCHAR(64) NOT NULL COMMENT '事件唯一ID（雪花）',
    `invite_id`   BIGINT      NOT NULL COMMENT '邀请码ID（业务ID）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消费时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_id` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邀请码计数事件去重表';

-- ============================================
-- 15. LLM 调用审计索引表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_llm_audit_log` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `request_id`          VARCHAR(64)  NOT NULL COMMENT '请求追踪ID',
    `tenant_id`           BIGINT       NOT NULL COMMENT '租户ID（业务ID）',
    `user_id`             BIGINT       NOT NULL COMMENT '用户ID（业务ID）',
    `api_key_id`          BIGINT       NOT NULL COMMENT 'API Key ID（业务ID）',
    `service_id`          BIGINT       NOT NULL COMMENT 'LLM服务ID（业务ID）',
    `model`               VARCHAR(100) NOT NULL COMMENT '实际调用模型别名',
    `prompt_preview`      VARCHAR(512) DEFAULT NULL COMMENT '输入 prompt 概略',
    `response_preview`    VARCHAR(512) DEFAULT NULL COMMENT '输出 response 概略',
    `prompt_object_key`   VARCHAR(512) DEFAULT NULL COMMENT '输入 prompt 在 S3 的 objectKey',
    `response_object_key` VARCHAR(512) DEFAULT NULL COMMENT '输出 response 在 S3 的 objectKey',
    `prompt_tokens`       INT          NOT NULL DEFAULT 0 COMMENT '输入Token数',
    `completion_tokens`   INT          NOT NULL DEFAULT 0 COMMENT '输出Token数',
    `total_tokens`        INT          NOT NULL DEFAULT 0 COMMENT '总Token数',
    `latency_ms`          INT          NOT NULL DEFAULT 0 COMMENT '调用耗时（毫秒）',
    `status`              VARCHAR(10)  NOT NULL COMMENT 'SUCCESS / FAIL',
    `error_code`          VARCHAR(20)  DEFAULT NULL COMMENT '失败时的错误码',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_request_id` (`request_id`),
    KEY `idx_tenant_time` (`tenant_id`, `create_time`),
    KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型调用审计索引表';
