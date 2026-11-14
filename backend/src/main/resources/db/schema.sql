-- Sentinel log_detector 数据库结构（符合 proceed/数据库设计说明.md）
-- 幂等建表；数据库 DATETIME 统一使用北京时间（UTC+8）。禁止存储未脱敏日志/客户端 IP。

CREATE TABLE IF NOT EXISTS demo_item
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255) NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='示例 CRUD 数据';

CREATE TABLE IF NOT EXISTS app_user
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash CHAR(60)     NOT NULL COMMENT 'BCrypt 哈希',
    display_name  VARCHAR(64)  NOT NULL DEFAULT '',
    role          VARCHAR(16)  NOT NULL COMMENT 'ADMIN / ANALYST',
    enabled       TINYINT      NOT NULL DEFAULT 1,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username),
    KEY idx_app_user_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台用户';

CREATE TABLE IF NOT EXISTS import_batch
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    created_by   BIGINT       NULL,
    source_type  VARCHAR(16)  NOT NULL COMMENT 'UPLOAD / GENERATOR',
    file_name    VARCHAR(255) NULL,
    file_sha256  CHAR(64)     NOT NULL,
    scenario_code VARCHAR(32) NULL,
    status       VARCHAR(16)  NOT NULL COMMENT 'PROCESSING / SUCCESS / PARTIAL / FAILED',
    total_lines  INT          NOT NULL DEFAULT 0,
    parsed_lines INT          NOT NULL DEFAULT 0,
    skipped_lines INT         NOT NULL DEFAULT 0,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_import_batch_sha256 (file_sha256),
    KEY idx_import_batch_created_by (created_by, created_at),
    KEY idx_import_batch_status (status),
    CONSTRAINT fk_import_batch_user FOREIGN KEY (created_by) REFERENCES app_user(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='日志导入批次';

-- 独立表保证旧批次及既有 import_batch 结构无需迁移；历史批次无回放参数。
CREATE TABLE IF NOT EXISTS scenario_replay
(
    batch_id BIGINT NOT NULL,
    algorithm_version INT NOT NULL,
    seed BIGINT NOT NULL,
    base_time BIGINT NOT NULL,
    line_count INT NOT NULL,
    PRIMARY KEY (batch_id),
    CONSTRAINT fk_scenario_replay_batch FOREIGN KEY (batch_id) REFERENCES import_batch(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='场景回放生成参数';

CREATE TABLE IF NOT EXISTS log_entry
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id        BIGINT       NOT NULL,
    line_no         INT          NOT NULL,
    log_time        DATETIME(3)  NOT NULL COMMENT '北京时间 UTC+8',
    client_ip_hash  CHAR(64)     NOT NULL COMMENT 'HMAC-SHA256',
    client_ip_mask  VARCHAR(64)  NOT NULL,
    method          VARCHAR(16)  NOT NULL,
    path            TEXT         NOT NULL,
    query_string    TEXT         NULL,
    status_code     SMALLINT     NOT NULL,
    response_bytes  BIGINT       NOT NULL DEFAULT 0,
    referer         TEXT         NULL,
    user_agent      TEXT         NULL,
    raw_line_masked TEXT         NOT NULL,
    parse_status    VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_log_entry_batch_line (batch_id, line_no),
    KEY idx_log_entry_client_time (client_ip_hash, log_time),
    KEY idx_log_entry_batch (batch_id),
    KEY idx_log_entry_time (log_time),
    CONSTRAINT fk_log_entry_batch FOREIGN KEY (batch_id) REFERENCES import_batch(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='解析并脱敏后的访问日志';

-- Serializes and records completed batch detections, including zero-hit batches.
CREATE TABLE IF NOT EXISTS detection_batch
(
    batch_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (batch_id),
    CONSTRAINT fk_detection_batch_import FOREIGN KEY (batch_id) REFERENCES import_batch(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rule_definition
(
    rule_code        VARCHAR(16)  NOT NULL,
    rule_name        VARCHAR(128) NOT NULL,
    category         VARCHAR(32)  NOT NULL,
    cwe              VARCHAR(32)  NULL,
    default_severity VARCHAR(16)  NOT NULL,
    threshold_config JSON        NULL,
    description      VARCHAR(512) NOT NULL DEFAULT '',
    enabled          TINYINT      NOT NULL DEFAULT 1,
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (rule_code),
    KEY idx_rule_definition_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='只读检测规则定义';

CREATE TABLE IF NOT EXISTS rule_hit
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    log_entry_id     BIGINT       NOT NULL,
    rule_code        VARCHAR(16)  NOT NULL,
    matched_field    VARCHAR(32)  NOT NULL,
    evidence_snippet VARCHAR(1024) NOT NULL,
    severity         VARCHAR(16)  NOT NULL,
    hit_time         DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_rule_hit_log_rule (log_entry_id, rule_code),
    KEY idx_rule_hit_rule_time (rule_code, hit_time),
    CONSTRAINT fk_rule_hit_log FOREIGN KEY (log_entry_id) REFERENCES log_entry(id) ON DELETE RESTRICT,
    CONSTRAINT fk_rule_hit_definition FOREIGN KEY (rule_code) REFERENCES rule_definition(rule_code) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='检测规则命中';

CREATE TABLE IF NOT EXISTS event
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    client_ip_hash CHAR(64)     NOT NULL,
    client_ip_mask VARCHAR(64)  NOT NULL,
    window_start   DATETIME(3)  NOT NULL,
    window_end     DATETIME(3)  NOT NULL,
    risk_score     DECIMAL(5,2) NOT NULL DEFAULT 0,
    severity       VARCHAR(16)  NOT NULL,
    status         VARCHAR(24)  NOT NULL DEFAULT 'NEW',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_event_ip_window (client_ip_hash, window_start, window_end),
    KEY idx_event_status (status),
    KEY idx_event_severity (severity),
    KEY idx_event_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聚合攻击事件';

CREATE TABLE IF NOT EXISTS event_evidence
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    event_id    BIGINT      NOT NULL,
    rule_hit_id BIGINT      NOT NULL,
    is_primary  TINYINT     NOT NULL DEFAULT 0,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_evidence (event_id, rule_hit_id),
    KEY idx_event_evidence_hit (rule_hit_id),
    CONSTRAINT fk_event_evidence_event FOREIGN KEY (event_id) REFERENCES event(id) ON DELETE RESTRICT,
    CONSTRAINT fk_event_evidence_rule_hit FOREIGN KEY (rule_hit_id) REFERENCES rule_hit(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件证据关联';

CREATE TABLE IF NOT EXISTS event_timeline
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    event_id      BIGINT       NOT NULL,
    actor_user_id BIGINT       NULL,
    action        VARCHAR(32)  NOT NULL,
    old_status    VARCHAR(24)  NULL,
    new_status    VARCHAR(24)  NULL,
    note          TEXT         NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_event_timeline_event_time (event_id, created_at),
    KEY idx_event_timeline_actor (actor_user_id, created_at),
    CONSTRAINT fk_event_timeline_event FOREIGN KEY (event_id) REFERENCES event(id) ON DELETE RESTRICT,
    CONSTRAINT fk_event_timeline_user FOREIGN KEY (actor_user_id) REFERENCES app_user(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件处置时间线';

CREATE TABLE IF NOT EXISTS ai_analysis
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    event_id            BIGINT        NOT NULL,
    requested_by        BIGINT        NULL,
    status              VARCHAR(24)   NOT NULL COMMENT 'SUCCESS / RULE_FALLBACK / FAILED',
    model               VARCHAR(128)  NOT NULL DEFAULT '',
    summary             TEXT          NULL,
    attack_chain        JSON          NULL,
    recommendations     JSON          NULL,
    confidence          DECIMAL(4,3)  NULL,
    false_positive_notes TEXT         NULL,
    prompt_tokens       INT           NOT NULL DEFAULT 0,
    completion_tokens   INT           NOT NULL DEFAULT 0,
    cost_ms             BIGINT        NOT NULL DEFAULT 0,
    created_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ai_analysis_event_time (event_id, created_at),
    KEY idx_ai_analysis_requester (requested_by, created_at),
    CONSTRAINT fk_ai_analysis_event FOREIGN KEY (event_id) REFERENCES event(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ai_analysis_user FOREIGN KEY (requested_by) REFERENCES app_user(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 研判记录';

CREATE TABLE IF NOT EXISTS analysis_cache_record
(
    analysis_id BIGINT NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    cache_source_id BIGINT NULL,
    saved_tokens INT NOT NULL DEFAULT 0,
    PRIMARY KEY (analysis_id),
    KEY idx_analysis_cache_fingerprint (fingerprint, analysis_id),
    CONSTRAINT fk_analysis_cache_analysis FOREIGN KEY (analysis_id) REFERENCES ai_analysis(id) ON DELETE RESTRICT,
    CONSTRAINT fk_analysis_cache_source FOREIGN KEY (cache_source_id) REFERENCES ai_analysis(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='研判缓存及节省估算';

CREATE TABLE IF NOT EXISTS analysis_model_attempt
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    model VARCHAR(128) NOT NULL DEFAULT '',
    outcome VARCHAR(24) NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_analysis_attempt_time (created_at),
    CONSTRAINT fk_analysis_attempt_event FOREIGN KEY (event_id) REFERENCES event(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件研判模型尝试记录';

CREATE TABLE IF NOT EXISTS ai_analysis_evidence
(
    analysis_id   BIGINT NOT NULL,
    log_entry_id BIGINT NOT NULL,
    citation_order INT NOT NULL DEFAULT 0,
    stage_no     INT NULL,
    PRIMARY KEY (analysis_id, log_entry_id),
    KEY idx_analysis_evidence_log (log_entry_id),
    CONSTRAINT fk_analysis_evidence_analysis FOREIGN KEY (analysis_id) REFERENCES ai_analysis(id) ON DELETE RESTRICT,
    CONSTRAINT fk_analysis_evidence_log FOREIGN KEY (log_entry_id) REFERENCES log_entry(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 研判证据引用';

CREATE TABLE IF NOT EXISTS audit_log
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT       NULL,
    action        VARCHAR(32)  NOT NULL,
    target_type   VARCHAR(32)  NOT NULL,
    target_id     VARCHAR(64)  NULL,
    detail        JSON         NULL COMMENT '脱敏后的审计详情',
    trace_id      VARCHAR(64)  NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_audit_actor_time (actor_user_id, created_at),
    KEY idx_audit_action_time (action, created_at),
    KEY idx_audit_trace_id (trace_id),
    CONSTRAINT fk_audit_user FOREIGN KEY (actor_user_id) REFERENCES app_user(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作审计';

-- 脚手架原有的 llm_call 表仍保留供公共调用记录 API 兼容；T4 事件级研判落在 ai_analysis。
CREATE TABLE IF NOT EXISTS llm_call
(
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    model             VARCHAR(64) NOT NULL,
    cost_ms           BIGINT      NOT NULL DEFAULT 0,
    prompt_tokens     INT         NOT NULL DEFAULT 0,
    completion_tokens INT         NOT NULL DEFAULT 0,
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_llm_call_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公共 LLM 调用记录';
