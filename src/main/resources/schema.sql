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
    `email`                 VARCHAR(128) NOT NULL COMMENT '邮箱',
    `phone`                 VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `status`                TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0=禁用, 1=正常',
    `last_active_tenant_id` BIGINT       DEFAULT NULL COMMENT '当前活跃租户ID',
    `create_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`              TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=未删除, 1=已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`)
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
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_id` (`tenant_id`),
    UNIQUE KEY `uk_owner_personal` (`owner_id`, `type`)
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
    `expires_at`  DATETIME    NOT NULL COMMENT '过期时间',
    `is_active`   TINYINT     NOT NULL DEFAULT 1 COMMENT '是否有效：0=已失效, 1=有效',
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
    `key_prefix`    VARCHAR(8)   NOT NULL COMMENT 'API Key前缀（明文，用于识别）',
    `name`          VARCHAR(50)  DEFAULT NULL COMMENT 'Key的备注名',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0=已吊销, 1=正常',
    `last_used_at`  DATETIME     DEFAULT NULL COMMENT '最近使用时间',
    `expires_at`    DATETIME     DEFAULT NULL COMMENT '过期时间（NULL=永不过期）',
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
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `del_flag`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_service_id` (`service_id`),
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
