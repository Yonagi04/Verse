-- 私人收藏不初始化默认夹，不回填源内容；回滚应用时保留这两张表。
CREATE TABLE t_skill_favorite_folder (
    folder_id BIGINT NOT NULL COMMENT '自定义收藏夹标识',
    user_id BIGINT NOT NULL COMMENT '个人账号归属',
    name VARCHAR(80) NOT NULL COMMENT '规范展示名称',
    name_key VARCHAR(80) COLLATE utf8mb4_bin NOT NULL COMMENT 'NFC小写唯一键',
    create_time DATETIME(3) NOT NULL COMMENT '创建时间UTC',
    update_time DATETIME(3) NOT NULL COMMENT '最近改名时间UTC',
    PRIMARY KEY (folder_id),
    UNIQUE KEY uk_fav_folder_user_name (user_id,name_key),
    KEY idx_fav_folder_user (user_id,create_time,folder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='个人自定义收藏夹';

CREATE TABLE t_skill_favorite (
    favorite_id BIGINT NOT NULL COMMENT '收藏关系标识',
    user_id BIGINT NOT NULL COMMENT '个人账号归属',
    skill_id BIGINT NOT NULL COMMENT '稳定Skill标识',
    folder_id BIGINT NOT NULL COMMENT '归属夹，0为默认',
    saved_at DATETIME(3) NOT NULL COMMENT '原始收藏时间UTC',
    create_time DATETIME(3) NOT NULL COMMENT '创建时间UTC',
    update_time DATETIME(3) NOT NULL COMMENT '最近整理时间UTC',
    PRIMARY KEY (favorite_id),
    UNIQUE KEY uk_fav_user_skill (user_id,skill_id),
    KEY idx_fav_user_saved (user_id,saved_at,favorite_id),
    KEY idx_fav_folder_saved (user_id,folder_id,saved_at,favorite_id),
    CONSTRAINT ck_fav_folder_nonnegative CHECK (folder_id >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='个人Skill收藏关系';
