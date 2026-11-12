-- JudgeX schema, version 1 (MySQL 8.0, utf8mb4).
--
-- Flyway owns this file: it runs once, on first boot against an empty database,
-- and its checksum is remembered. From now on every schema change is a NEW script
-- (V2__add_x.sql, V3__...), never an edit of an applied one.

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(64)  NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE problems (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    slug            VARCHAR(64)  NOT NULL,
    title           VARCHAR(128) NOT NULL,
    difficulty      VARCHAR(16)  NOT NULL,
    tags            VARCHAR(255) NOT NULL DEFAULT '',
    statement       TEXT         NOT NULL,
    time_limit_ms   INT          NOT NULL DEFAULT 2000,
    memory_limit_mb INT          NOT NULL DEFAULT 256,
    published       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_problems_slug (slug)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE test_cases (
    id              BIGINT     NOT NULL AUTO_INCREMENT,
    problem_id      BIGINT     NOT NULL,
    ordinal         INT        NOT NULL,
    input           TEXT       NOT NULL,
    expected_output TEXT       NOT NULL,
    points          INT        NOT NULL DEFAULT 10,
    hidden          TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY ix_cases_problem (problem_id, ordinal),
    CONSTRAINT fk_case_problem FOREIGN KEY (problem_id) REFERENCES problems (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE submissions (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    user_id        BIGINT      NOT NULL,
    problem_id     BIGINT      NOT NULL,
    language       VARCHAR(16) NOT NULL,
    source_code    TEXT        NOT NULL,
    status         VARCHAR(16) NOT NULL,
    verdict        VARCHAR(16) NULL,
    score          INT         NOT NULL DEFAULT 0,
    idem_key       VARCHAR(64) NULL,
    compile_output TEXT        NULL,
    total_time_ms  BIGINT      NOT NULL DEFAULT 0,
    created_at     DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at     DATETIME   NULL DEFAULT NULL,
    finished_at    DATETIME   NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_sub_idem (user_id, idem_key),
    KEY ix_sub_user_time (user_id, created_at DESC),
    KEY ix_sub_problem (problem_id, created_at DESC),
    CONSTRAINT fk_sub_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_sub_problem FOREIGN KEY (problem_id) REFERENCES problems (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE test_results (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    submission_id BIGINT      NOT NULL,
    case_id       BIGINT      NOT NULL,
    ordinal       INT         NOT NULL,
    verdict       VARCHAR(16) NOT NULL,
    time_ms       INT         NOT NULL DEFAULT 0,
    memory_kb     INT         NOT NULL DEFAULT 0,
    stdout        TEXT        NULL,
    stderr        TEXT        NULL,
    points        INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_res_sub (submission_id, ordinal),
    CONSTRAINT fk_res_sub FOREIGN KEY (submission_id) REFERENCES submissions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE judge_tasks (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    submission_id    BIGINT       NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    worker_id        VARCHAR(64)  NULL,
    attempts         INT          NOT NULL DEFAULT 0,
    lease_expires_at DATETIME    NULL DEFAULT NULL,
    last_error       VARCHAR(512) NULL,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_submission (submission_id),
    KEY ix_task_status (status, id),
    CONSTRAINT fk_task_sub FOREIGN KEY (submission_id) REFERENCES submissions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workers (
    id             VARCHAR(64)  NOT NULL,
    host           VARCHAR(128) NOT NULL,
    concurrency    INT          NOT NULL,
    active_tasks   INT          NOT NULL DEFAULT 0,
    last_heartbeat DATETIME    NOT NULL,
    started_at     DATETIME    NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE audit_log (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    actor      VARCHAR(64)  NOT NULL,
    action     VARCHAR(64)  NOT NULL,
    detail     VARCHAR(512) NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Transactional outbox: written in the same transaction as the verdict.
CREATE TABLE outbox (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    event_key       VARCHAR(96)  NOT NULL,
    topic           VARCHAR(96)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME    NOT NULL,
    created_at      DATETIME    NOT NULL,
    sent_at         DATETIME    NULL DEFAULT NULL,
    last_error      VARCHAR(512) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event (event_key),
    KEY ix_outbox_due (status, next_attempt_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Consumer side of the outbox; the unique key makes at-least-once delivery idempotent.
CREATE TABLE notifications (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    submission_id BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    verdict       VARCHAR(16)  NOT NULL,
    message       VARCHAR(255) NOT NULL,
    created_at    DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_submission (submission_id),
    KEY ix_notifications_user (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
