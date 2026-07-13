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
