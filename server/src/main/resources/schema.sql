CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) NOT NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50) NOT NULL,
    avatar VARCHAR(512) NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'USER',
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS media_files (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    content_hash VARCHAR(64) NULL,
    ai_summary LONGTEXT NULL,
    transcript_text LONGTEXT NULL,
    cover_url VARCHAR(1024) NULL,
    upload_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_media_content_hash (content_hash),
    KEY idx_media_user_time (user_id, upload_time),
    KEY idx_media_status_time (status, upload_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @content_hash_column_exists = (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'media_files' AND column_name = 'content_hash'
);
SET @content_hash_column_sql = IF(
    @content_hash_column_exists = 0,
    'ALTER TABLE media_files ADD COLUMN content_hash VARCHAR(64) NULL, ALGORITHM=INSTANT, LOCK=NONE',
    'SELECT 1'
);
PREPARE content_hash_column_statement FROM @content_hash_column_sql;
EXECUTE content_hash_column_statement;
DEALLOCATE PREPARE content_hash_column_statement;
SET @content_hash_index_exists = (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'media_files' AND index_name = 'idx_media_content_hash'
);
SET @content_hash_index_sql = IF(
    @content_hash_index_exists = 0,
    'ALTER TABLE media_files ADD INDEX idx_media_content_hash(content_hash), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1'
);
PREPARE content_hash_index_statement FROM @content_hash_index_sql;
EXECUTE content_hash_index_statement;
DEALLOCATE PREPARE content_hash_index_statement;

CREATE TABLE IF NOT EXISTS agent_checkpoints (
    media_id BIGINT NOT NULL,
    checkpoint_key VARCHAR(160) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    payload LONGTEXT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (media_id, checkpoint_key),
    KEY idx_agent_checkpoint_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS failed_analysis_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    media_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    user_goal VARCHAR(500) NOT NULL,
    attempt_count INT NOT NULL,
    error_type VARCHAR(128) NOT NULL,
    error_message VARCHAR(1000) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'FAILED',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_failed_analysis_status_time (status, created_at),
    KEY idx_failed_analysis_media (media_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
