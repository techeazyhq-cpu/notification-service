--liquibase formatted sql

--changeset notification:001-baseline
--comment Initial schema (formerly Flyway V1). Databases created by Flyway already have it, so this is marked as run there.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'client'
CREATE TABLE client (
    id               UUID PRIMARY KEY,
    name             VARCHAR(120) NOT NULL UNIQUE,
    api_key_hash     VARCHAR(64)  NOT NULL UNIQUE,
    api_key_prefix   VARCHAR(12)  NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    allowed_channels VARCHAR(100) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

CREATE TABLE template (
    id         UUID PRIMARY KEY,
    name       VARCHAR(120) NOT NULL UNIQUE,
    channel    VARCHAR(16)  NOT NULL,
    subject    VARCHAR(500),
    body       TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE provider_config (
    id         UUID PRIMARY KEY,
    channel    VARCHAR(16)  NOT NULL,
    name       VARCHAR(120) NOT NULL UNIQUE,
    type       VARCHAR(24)  NOT NULL,
    settings   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    enabled    BOOLEAN      NOT NULL,
    priority   INT          NOT NULL DEFAULT 100,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE rate_limit_policy (
    id              UUID PRIMARY KEY,
    scope           VARCHAR(24)   NOT NULL,
    client_id       UUID REFERENCES client (id) ON DELETE CASCADE,
    channel         VARCHAR(16),
    rate_per_second NUMERIC(12,3) NOT NULL,
    burst           INT           NOT NULL,
    enabled         BOOLEAN       NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL
);
CREATE UNIQUE INDEX ux_rate_limit_scope ON rate_limit_policy
    (scope, COALESCE(client_id, '00000000-0000-0000-0000-000000000000'::uuid), COALESCE(channel, '*'));

CREATE TABLE notification_request (
    id                UUID PRIMARY KEY,
    client_id         UUID         NOT NULL REFERENCES client (id),
    kind              VARCHAR(8)   NOT NULL,
    channel           VARCHAR(16)  NOT NULL,
    template_id       UUID REFERENCES template (id),
    subject           VARCHAR(500),
    body              TEXT,
    total             INT          NOT NULL,
    idempotency_key   VARCHAR(120),
    client_reference  VARCHAR(120),
    created_at        TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX ux_request_idempotency ON notification_request (client_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_request_created ON notification_request (created_at DESC);

CREATE TABLE notification_message (
    id                  UUID PRIMARY KEY,
    request_id          UUID         NOT NULL REFERENCES notification_request (id),
    client_id           UUID         NOT NULL,
    channel             VARCHAR(16)  NOT NULL,
    recipient           VARCHAR(320) NOT NULL,
    variables           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status              VARCHAR(16)  NOT NULL,
    attempts            INT          NOT NULL DEFAULT 0,
    last_error          VARCHAR(1000),
    provider_message_id VARCHAR(200),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    sent_at             TIMESTAMPTZ
);
CREATE INDEX ix_message_request_status ON notification_message (request_id, status);
CREATE INDEX ix_message_status_updated ON notification_message (status, updated_at);
CREATE INDEX ix_message_created ON notification_message (created_at);
--rollback DROP TABLE notification_message;
--rollback DROP TABLE notification_request;
--rollback DROP TABLE rate_limit_policy;
--rollback DROP TABLE provider_config;
--rollback DROP TABLE template;
--rollback DROP TABLE client;
