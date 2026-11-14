-- Incremental MySQL migration: safe to repeat; retains all existing data.
-- Select the application database before executing. Requires import_batch(id).
-- Existing deployments must be backed up before schema changes.
CREATE TABLE IF NOT EXISTS detection_batch
(
    batch_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (batch_id),
    CONSTRAINT fk_detection_batch_import FOREIGN KEY (batch_id)
        REFERENCES import_batch(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
