CREATE TABLE IF NOT EXISTS users (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(64)  NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS problems (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    slug           VARCHAR(64)  NOT NULL UNIQUE,
    title          VARCHAR(128) NOT NULL,
    difficulty     VARCHAR(16)  NOT NULL,
    tags           VARCHAR(255) NOT NULL DEFAULT '',
    statement      TEXT         NOT NULL,
    time_limit_ms  INT          NOT NULL DEFAULT 2000,
    memory_limit_mb INT         NOT NULL DEFAULT 256,
    published      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS test_cases (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    problem_id      BIGINT NOT NULL,
    ordinal         INT    NOT NULL,
    input           TEXT   NOT NULL,
    expected_output TEXT   NOT NULL,
    points          INT    NOT NULL DEFAULT 10,
    hidden          BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_case_problem FOREIGN KEY (problem_id) REFERENCES problems (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_cases_problem ON test_cases (problem_id, ordinal);

CREATE TABLE IF NOT EXISTS submissions (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    problem_id    BIGINT      NOT NULL,
    language      VARCHAR(16) NOT NULL,
    source_code   TEXT        NOT NULL,
    status        VARCHAR(16) NOT NULL,
    verdict       VARCHAR(16),
    score         INT         NOT NULL DEFAULT 0,
    idem_key      VARCHAR(64),
    compile_output TEXT,
    total_time_ms BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    CONSTRAINT fk_sub_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_sub_problem FOREIGN KEY (problem_id) REFERENCES problems (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_sub_idem ON submissions (user_id, idem_key);
CREATE INDEX IF NOT EXISTS ix_sub_user_time ON submissions (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_sub_problem ON submissions (problem_id, created_at DESC);

CREATE TABLE IF NOT EXISTS test_results (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id BIGINT      NOT NULL,
    case_id       BIGINT      NOT NULL,
    ordinal       INT         NOT NULL,
    verdict       VARCHAR(16) NOT NULL,
    time_ms       INT         NOT NULL DEFAULT 0,
    memory_kb     INT         NOT NULL DEFAULT 0,
    stdout        TEXT,
    stderr        TEXT,
    points        INT         NOT NULL DEFAULT 0,
    CONSTRAINT fk_res_sub FOREIGN KEY (submission_id) REFERENCES submissions (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_res_sub ON test_results (submission_id, ordinal);

CREATE TABLE IF NOT EXISTS judge_tasks (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id    BIGINT      NOT NULL UNIQUE,
    status           VARCHAR(16) NOT NULL,
    worker_id        VARCHAR(64),
    attempts         INT         NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMP,
    last_error       VARCHAR(512),
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_task_sub FOREIGN KEY (submission_id) REFERENCES submissions (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_task_status ON judge_tasks (status, id);

CREATE TABLE IF NOT EXISTS workers (
    id             VARCHAR(64) PRIMARY KEY,
    host           VARCHAR(128) NOT NULL,
    concurrency    INT          NOT NULL,
    active_tasks   INT          NOT NULL DEFAULT 0,
    last_heartbeat TIMESTAMP    NOT NULL,
    started_at     TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor      VARCHAR(64) NOT NULL,
    action     VARCHAR(64) NOT NULL,
    detail     VARCHAR(512),
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Transactional outbox: written in the same transaction as the verdict.
CREATE TABLE IF NOT EXISTS outbox (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_key       VARCHAR(96)  NOT NULL,
    topic           VARCHAR(96)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP    NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    sent_at         TIMESTAMP    NULL,
    last_error      VARCHAR(512) NULL,
    CONSTRAINT uk_outbox_event UNIQUE (event_key)
);
CREATE INDEX IF NOT EXISTS ix_outbox_due ON outbox (status, next_attempt_at);

-- Consumer side of the outbox; unique key makes at-least-once delivery idempotent.
CREATE TABLE IF NOT EXISTS notifications (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    verdict       VARCHAR(16)  NOT NULL,
    message       VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uk_notification_submission UNIQUE (submission_id)
);
CREATE INDEX IF NOT EXISTS ix_notifications_user ON notifications (user_id, id);
