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
    KEY `idx_email_hash` (`email_hash`),
    KEY `idx_last_active_tenant_id` (`last_active_tenant_id`)
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
    `logo`        VARCHAR(512) DEFAULT NULL COMMENT '租户Logo在S3中的objectKey',
    `banner`      VARCHAR(512) DEFAULT NULL COMMENT '租户头图在S3中的objectKey',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0=停用, 1=正常',
    `join_approval_mode` TINYINT NOT NULL DEFAULT 0 COMMENT '加入审批模式：0=直接加入, 1=管理员审批；TODO 2=多级审批',
    `rate_limit_rpm`   INT      DEFAULT NULL COMMENT '租户级 RPM 上限（NULL=不限）',
    `rate_limit_tpm`   INT      DEFAULT NULL COMMENT '租户级 TPM 上限（NULL=不限）',
    `audit_enabled`    TINYINT  NOT NULL DEFAULT 0 COMMENT '是否开启模型调用审计：0=关闭, 1=开启',
    `activity_recording_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '是否开启租户动态记录：0=关闭, 1=开启',
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
    `description` VARCHAR(255) DEFAULT NULL COMMENT '模型介绍',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0=禁用, 1=启用',
    `created_by`  BIGINT       NOT NULL COMMENT '创建者用户ID',
    `rate_limit_rpm` INT       DEFAULT NULL COMMENT '模型级 RPM 上限（NULL=不限）',
    `rate_limit_tpm` INT       DEFAULT NULL COMMENT '模型级 TPM 上限（NULL=不限）',
    `fallback_service_id` BIGINT DEFAULT NULL COMMENT '备用模型 serviceId（单级降级，NULL=无降级）',
    `context_window` BIGINT DEFAULT NULL COMMENT '上下文长度',
    `max_output_tokens` BIGINT DEFAULT NULL COMMENT '最大输出 Token',
    `active_pricing_id` BIGINT DEFAULT NULL COMMENT '当前计费版本业务 ID',
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
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT '状态：SUCCESS / ABORTED / FAIL',
    `usage_source`      VARCHAR(16)  NOT NULL DEFAULT 'EXACT' COMMENT 'usage来源：EXACT / ESTIMATED / UNKNOWN',
    `event_id`          VARCHAR(64)  DEFAULT NULL COMMENT '消息幂等键',
    `request_started_at` DATETIME(3) DEFAULT NULL COMMENT '网关请求开始时间',
    `input_tokens` BIGINT DEFAULT NULL,
    `cached_input_tokens` BIGINT DEFAULT NULL,
    `cache_write_input_tokens` BIGINT DEFAULT NULL,
    `output_tokens` BIGINT DEFAULT NULL,
    `normalized_total_tokens` BIGINT DEFAULT NULL,
    `usage_parser` VARCHAR(40) DEFAULT NULL,
    `pricing_id` BIGINT DEFAULT NULL,
    `billing_mode` VARCHAR(16) DEFAULT NULL,
    `price_period_type` VARCHAR(16) DEFAULT NULL,
    `price_period_id` BIGINT DEFAULT NULL,
    `price_effective_from` DATETIME(3) DEFAULT NULL,
    `price_effective_to` DATETIME(3) DEFAULT NULL,
    `cache_miss_input_price_fen` DECIMAL(30,12) DEFAULT NULL,
    `cache_hit_input_price_fen` DECIMAL(30,12) DEFAULT NULL,
    `output_price_fen` DECIMAL(30,12) DEFAULT NULL,
    `request_price_fen` DECIMAL(30,12) DEFAULT NULL,
    `estimated_cost_fen` DECIMAL(38,18) DEFAULT NULL,
    `cost_status` VARCHAR(24) NOT NULL DEFAULT 'UNPRICED',
    `currency` CHAR(3) DEFAULT NULL,
    `usage_details_json` JSON DEFAULT NULL,
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_tenant_time` (`user_id`, `tenant_id`, `create_time`),
    KEY `idx_tenant_time` (`tenant_id`, `create_time`),
    KEY `idx_usage_tenant_cost_started` (`tenant_id`, `cost_status`, `request_started_at`),
    KEY `idx_usage_tenant_user_cost_started` (`tenant_id`, `user_id`, `cost_status`, `request_started_at`),
    UNIQUE KEY `uk_token_usage_event_id` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Token消耗记录表';

CREATE TABLE IF NOT EXISTS `t_token_usage_cost` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `usage_id` BIGINT NOT NULL COMMENT 'Token用量事实主键ID',
    `pricing_id` BIGINT DEFAULT NULL COMMENT '定价版本业务ID快照',
    `billing_mode` VARCHAR(16) DEFAULT NULL COMMENT '计费模式快照',
    `price_period_type` VARCHAR(16) DEFAULT NULL COMMENT '价格时段类型快照',
    `price_period_id` BIGINT DEFAULT NULL COMMENT '价格时段业务ID快照',
    `price_effective_from` DATETIME(3) DEFAULT NULL COMMENT '定价生效时间快照',
    `price_effective_to` DATETIME(3) DEFAULT NULL COMMENT '定价失效时间快照',
    `cache_miss_input_price_fen` DECIMAL(30,12) DEFAULT NULL COMMENT '缓存未命中输入单价快照，分/百万Token',
    `cache_hit_input_price_fen` DECIMAL(30,12) DEFAULT NULL COMMENT '缓存命中输入单价快照，分/百万Token',
    `output_price_fen` DECIMAL(30,12) DEFAULT NULL COMMENT '输出单价快照，分/百万Token',
    `request_price_fen` DECIMAL(30,12) DEFAULT NULL COMMENT '单次请求价格快照，分',
    `estimated_cost_fen` DECIMAL(38,18) DEFAULT NULL COMMENT '预估费用，分',
    `cost_status` VARCHAR(24) NOT NULL COMMENT 'CALCULATED/UNCALCULABLE/UNPRICED/NOT_CHARGEABLE',
    `currency` CHAR(3) DEFAULT NULL COMMENT '计费币种',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_usage_cost_usage` (`usage_id`),
    KEY `idx_token_usage_cost_status` (`cost_status`),
    CONSTRAINT `fk_token_usage_cost_usage` FOREIGN KEY (`usage_id`) REFERENCES `t_token_usage` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Token用量费用快照表';

CREATE TABLE IF NOT EXISTS `t_token_usage_hourly_agg` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` BIGINT NOT NULL COMMENT '租户业务ID',
    `user_id` BIGINT NOT NULL COMMENT '用户业务ID',
    `api_key_id` BIGINT NOT NULL COMMENT 'API Key业务ID',
    `service_id` BIGINT NOT NULL COMMENT 'LLM服务业务ID',
    `model` VARCHAR(100) NOT NULL COMMENT '实际调用模型名快照',
    `bucket_start` DATETIME NOT NULL COMMENT 'Asia/Shanghai小时桶起点',
    `input_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT '输入Token合计',
    `cached_input_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT '缓存命中输入Token合计',
    `cache_write_input_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT '缓存写入输入Token合计',
    `output_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT '输出Token合计',
    `total_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT '总Token合计',
    `request_count` BIGINT NOT NULL DEFAULT 0 COMMENT '终态请求数',
    `success_request_count` BIGINT NOT NULL DEFAULT 0 COMMENT '成功请求数',
    `exact_usage_count` BIGINT NOT NULL DEFAULT 0 COMMENT '精确用量请求数',
    `estimated_usage_count` BIGINT NOT NULL DEFAULT 0 COMMENT '预估用量请求数',
    `unknown_usage_count` BIGINT NOT NULL DEFAULT 0 COMMENT '未知用量请求数',
    `estimated_cost_fen` DECIMAL(38,18) NOT NULL DEFAULT 0 COMMENT '可计算预估费用合计，分',
    `calculated_count` BIGINT NOT NULL DEFAULT 0 COMMENT '费用已计算请求数',
    `unpriced_count` BIGINT NOT NULL DEFAULT 0 COMMENT '未启用计费请求数',
    `uncalculable_count` BIGINT NOT NULL DEFAULT 0 COMMENT '费用无法计算请求数',
    `not_chargeable_count` BIGINT NOT NULL DEFAULT 0 COMMENT '不可计费请求数',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_usage_hourly_grain` (`tenant_id`,`user_id`,`api_key_id`,`service_id`,`model`,`bucket_start`),
    KEY `idx_usage_hourly_tenant_bucket` (`tenant_id`,`bucket_start`),
    KEY `idx_usage_hourly_tenant_user_bucket` (`tenant_id`,`user_id`,`bucket_start`),
    KEY `idx_usage_hourly_tenant_apikey_bucket` (`tenant_id`,`api_key_id`,`bucket_start`),
    KEY `idx_usage_hourly_tenant_service_bucket` (`tenant_id`,`service_id`,`bucket_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Token用量小时预聚合表';

-- ============================================
-- 7.0.1 计费用量事件持久化发送表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_token_usage_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `event_id` VARCHAR(64) NOT NULL COMMENT '稳定事件ID',
    `tenant_id` BIGINT NOT NULL COMMENT '租户ID',
    `event_type` VARCHAR(40) NOT NULL COMMENT 'RocketMQ 标签',
    `message_key` VARCHAR(128) NOT NULL COMMENT 'RocketMQ 顺序键',
    `payload_json` JSON NOT NULL COMMENT '不可变事件载荷',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CLAIMED/RETRY/FAILED/PUBLISHED',
    `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '发送尝试次数',
    `next_retry_at` DATETIME(3) NOT NULL COMMENT '下次可重试时间',
    `claim_owner` VARCHAR(64) DEFAULT NULL COMMENT '声明实例',
    `claim_expires_at` DATETIME(3) DEFAULT NULL COMMENT '声明租约到期时间',
    `last_error` VARCHAR(2000) DEFAULT NULL COMMENT '最近发送错误',
    `published_at` DATETIME(3) DEFAULT NULL COMMENT 'Broker确认接收时间',
    `reconciled_at` DATETIME(3) DEFAULT NULL COMMENT '事实表对账完成时间',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_usage_outbox_event` (`event_id`),
    KEY `idx_usage_outbox_claim` (`status`, `next_retry_at`, `claim_expires_at`, `id`),
    KEY `idx_usage_outbox_retention` (`status`, `reconciled_at`),
    KEY `idx_usage_outbox_tenant` (`tenant_id`, `event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计费用量事件持久化发送表';

-- ============================================
-- 7.0.2 通用可靠领域事件 Outbox
-- ============================================
CREATE TABLE IF NOT EXISTS `t_domain_event_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `event_id` VARCHAR(64) NOT NULL COMMENT '稳定事件ID',
    `tenant_id` BIGINT NOT NULL COMMENT '租户ID',
    `event_type` VARCHAR(40) NOT NULL COMMENT 'RocketMQ标签',
    `message_key` VARCHAR(128) NOT NULL COMMENT 'RocketMQ顺序路由键',
    `payload_json` JSON NOT NULL COMMENT '不可变事件载荷',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CLAIMED/RETRY/FAILED/PUBLISHED',
    `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '发送尝试次数',
    `next_retry_at` DATETIME(3) NOT NULL COMMENT '下次可重试时间',
    `claim_owner` VARCHAR(64) DEFAULT NULL COMMENT '声明实例',
    `claim_expires_at` DATETIME(3) DEFAULT NULL COMMENT '声明租约到期时间',
    `last_error` VARCHAR(2000) DEFAULT NULL COMMENT '最近发送或消费错误摘要',
    `published_at` DATETIME(3) DEFAULT NULL COMMENT 'Broker确认接收时间',
    `reconciled_at` DATETIME(3) DEFAULT NULL COMMENT '事实表对账完成时间',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_domain_outbox_event` (`event_id`),
    KEY `idx_domain_outbox_claim` (`status`, `next_retry_at`, `claim_expires_at`, `id`),
    KEY `idx_domain_outbox_retention` (`status`, `reconciled_at`),
    KEY `idx_domain_outbox_tenant` (`tenant_id`, `event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通用可靠领域事件Outbox';

-- ============================================
-- 7.0.3 租户动态事实表
-- ============================================
CREATE TABLE IF NOT EXISTS `t_tenant_activity_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID及稳定游标',
    `event_id` VARCHAR(64) NOT NULL COMMENT '幂等事件ID',
    `tenant_id` BIGINT NOT NULL COMMENT '租户ID',
    `category` VARCHAR(32) NOT NULL COMMENT '动态分类',
    `activity_type` VARCHAR(64) NOT NULL COMMENT '动态类型',
    `actor_user_id` BIGINT NOT NULL COMMENT '操作人用户ID',
    `actor_username` VARCHAR(64) NOT NULL COMMENT '操作人用户名快照',
    `actor_nickname` VARCHAR(100) DEFAULT NULL COMMENT '操作人昵称快照',
    `target_type` VARCHAR(32) DEFAULT NULL COMMENT '目标对象类型',
    `target_id` VARCHAR(64) DEFAULT NULL COMMENT '目标对象业务ID',
    `target_name` VARCHAR(255) DEFAULT NULL COMMENT '目标对象名称快照',
    `detail_json` JSON NOT NULL COMMENT '白名单化结构详情',
    `schema_version` INT NOT NULL COMMENT '事件结构版本',
    `occurred_at` DATETIME(3) NOT NULL COMMENT '业务发生时间',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '消费落库时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_activity_event` (`event_id`),
    KEY `idx_tenant_activity_timeline` (`tenant_id`, `occurred_at` DESC, `id` DESC),
    KEY `idx_tenant_activity_type_timeline` (`tenant_id`, `activity_type`, `occurred_at` DESC, `id` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户动态事实表';

-- ============================================
-- 7.1 LLM 标签与计费版本
-- ============================================
CREATE TABLE IF NOT EXISTS `t_llm_tag` (
    `tag_code` VARCHAR(40) NOT NULL,
    `display_name` VARCHAR(40) NOT NULL,
    `description` VARCHAR(255) NOT NULL,
    `sort_order` SMALLINT NOT NULL,
    PRIMARY KEY (`tag_code`),
    UNIQUE KEY `uk_llm_tag_sort_order` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM 固定能力标签字典';

CREATE TABLE IF NOT EXISTS `t_llm_service_tag` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `service_id` BIGINT NOT NULL,
    `tag_code` VARCHAR(40) NOT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_llm_service_tag` (`service_id`, `tag_code`),
    KEY `idx_llm_service_tag_code` (`tag_code`, `service_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM 服务能力标签关联';

CREATE TABLE IF NOT EXISTS `t_llm_service_pricing` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `pricing_id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `service_id` BIGINT NOT NULL,
    `billing_mode` VARCHAR(16) NOT NULL,
    `currency` CHAR(3) NOT NULL DEFAULT 'CNY',
    `base_cache_miss_input_price_fen` DECIMAL(30,12) DEFAULT NULL,
    `base_cache_hit_input_price_fen` DECIMAL(30,12) DEFAULT NULL,
    `base_output_price_fen` DECIMAL(30,12) DEFAULT NULL,
    `base_request_price_fen` DECIMAL(30,12) DEFAULT NULL,
    `effective_from` DATETIME(3) NOT NULL,
    `effective_to` DATETIME(3) DEFAULT NULL,
    `created_by` BIGINT NOT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_llm_pricing_id` (`pricing_id`),
    KEY `idx_llm_pricing_lookup` (`tenant_id`, `service_id`, `effective_from`, `effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM 服务不可变计费版本';

CREATE TABLE IF NOT EXISTS `t_llm_pricing_peak_period` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `period_id` BIGINT NOT NULL,
    `pricing_id` BIGINT NOT NULL,
    `weekday_mask` TINYINT UNSIGNED NOT NULL,
    `start_minute` SMALLINT UNSIGNED NOT NULL,
    `end_minute` SMALLINT UNSIGNED NOT NULL,
    `peak_cache_miss_input_price_fen` DECIMAL(30,12) DEFAULT NULL,
    `peak_cache_hit_input_price_fen` DECIMAL(30,12) DEFAULT NULL,
    `peak_output_price_fen` DECIMAL(30,12) DEFAULT NULL,
    `peak_request_price_fen` DECIMAL(30,12) DEFAULT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_llm_peak_period_id` (`period_id`),
    KEY `idx_llm_peak_pricing` (`pricing_id`),
    CHECK (`start_minute` < `end_minute` AND `end_minute` <= 1440)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM 计费高峰期规则';

INSERT INTO `t_llm_tag` (`tag_code`, `display_name`, `description`, `sort_order`) VALUES
('text-generation', '文本生成', '支持文本理解与生成', 1),
('tool-calling', '工具调用', '支持结构化工具调用', 2),
('image-understanding', '图像理解', '支持图像输入和理解', 3),
('image-generation', '图像生成', '支持图像生成', 4),
('video-generation', '视频生成', '支持视频生成', 5),
('embedding', '向量嵌入', '支持文本向量嵌入', 6),
('audio-input', '音频输入', '支持音频输入', 7),
('audio-output', '音频输出', '支持音频输出', 8),
('reasoning', '推理', '支持推理类模型能力', 9),
('structured-output', '结构化输出', '支持结构化输出', 10)
ON DUPLICATE KEY UPDATE `display_name` = VALUES(`display_name`), `description` = VALUES(`description`), `sort_order` = VALUES(`sort_order`);

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
