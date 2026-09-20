--liquibase formatted sql

--changeset notification:004-billing
--comment Billing: plans and rates, billing accounts, prepaid credit ledger and holds, invoices, and the usage index (see ADR-004).
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'billing_plan'
CREATE TABLE billing_plan (
    id           UUID PRIMARY KEY,
    name         VARCHAR(120)  NOT NULL UNIQUE,
    currency     CHAR(3)       NOT NULL,
    platform_fee NUMERIC(19,6) NOT NULL DEFAULT 0,
    tax_rate     NUMERIC(6,4)  NOT NULL DEFAULT 0,
    active       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_plan_tax_rate CHECK (tax_rate >= 0 AND tax_rate <= 1),
    CONSTRAINT ck_plan_platform_fee CHECK (platform_fee >= 0)
);

CREATE TABLE billing_plan_rate (
    plan_id        UUID          NOT NULL REFERENCES billing_plan (id) ON DELETE CASCADE,
    channel        VARCHAR(16)   NOT NULL,
    unit_price     NUMERIC(19,6) NOT NULL,
    free_allowance BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (plan_id, channel),
    CONSTRAINT ck_rate_price CHECK (unit_price >= 0),
    CONSTRAINT ck_rate_allowance CHECK (free_allowance >= 0)
);

CREATE TABLE billing_account (
    client_id         UUID PRIMARY KEY REFERENCES client (id),
    plan_id           UUID          NOT NULL REFERENCES billing_plan (id),
    mode              VARCHAR(8)    NOT NULL,
    monthly_spend_cap NUMERIC(19,6),
    credit_balance    NUMERIC(19,6) NOT NULL DEFAULT 0,
    status            VARCHAR(12)   NOT NULL,
    billing_email     VARCHAR(320),
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_account_mode CHECK (mode IN ('POSTPAID', 'PREPAID')),
    CONSTRAINT ck_account_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_account_balance CHECK (credit_balance >= 0),
    CONSTRAINT ck_account_cap CHECK (monthly_spend_cap IS NULL OR monthly_spend_cap >= 0)
);

CREATE TABLE credit_ledger_entry (
    id          UUID PRIMARY KEY,
    client_id   UUID          NOT NULL REFERENCES client (id),
    type        VARCHAR(16)   NOT NULL,
    amount      NUMERIC(19,6) NOT NULL,
    reference   VARCHAR(200)  NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ   NOT NULL
);
CREATE UNIQUE INDEX ux_ledger_reference ON credit_ledger_entry (client_id, type, reference);
CREATE INDEX ix_ledger_client_created ON credit_ledger_entry (client_id, created_at DESC);

CREATE TABLE credit_hold (
    id             UUID PRIMARY KEY,
    client_id      UUID          NOT NULL REFERENCES client (id),
    scope          VARCHAR(8)    NOT NULL,
    reference_id   UUID          NOT NULL,
    channel        VARCHAR(16)   NOT NULL,
    message_count  BIGINT        NOT NULL,
    unit_price     NUMERIC(19,6) NOT NULL,
    amount         NUMERIC(19,6) NOT NULL,
    status         VARCHAR(8)    NOT NULL,
    settled_charge NUMERIC(19,6),
    created_at     TIMESTAMPTZ   NOT NULL,
    settled_at     TIMESTAMPTZ,
    CONSTRAINT ck_hold_scope CHECK (scope IN ('REQUEST', 'MESSAGE')),
    CONSTRAINT ck_hold_status CHECK (status IN ('HELD', 'SETTLED'))
);
CREATE UNIQUE INDEX ux_hold_request ON credit_hold (reference_id) WHERE scope = 'REQUEST';
CREATE INDEX ix_hold_open ON credit_hold (created_at) WHERE status = 'HELD';
CREATE INDEX ix_hold_client_open ON credit_hold (client_id) WHERE status = 'HELD';

CREATE SEQUENCE invoice_number_seq;

CREATE TABLE invoice (
    id           UUID PRIMARY KEY,
    number       VARCHAR(32),
    client_id    UUID          NOT NULL REFERENCES client (id),
    plan_id      UUID          REFERENCES billing_plan (id),
    period_start DATE          NOT NULL,
    currency     CHAR(3)       NOT NULL,
    subtotal     NUMERIC(19,6) NOT NULL,
    tax_rate     NUMERIC(6,4)  NOT NULL,
    tax_amount   NUMERIC(19,6) NOT NULL,
    total        NUMERIC(19,6) NOT NULL,
    status       VARCHAR(8)    NOT NULL,
    issued_at    TIMESTAMPTZ,
    due_at       TIMESTAMPTZ,
    paid_at      TIMESTAMPTZ,
    voided_at    TIMESTAMPTZ,
    void_reason  VARCHAR(500),
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_invoice_status CHECK (status IN ('DRAFT', 'ISSUED', 'PAID', 'VOID'))
);
CREATE UNIQUE INDEX ux_invoice_number ON invoice (number) WHERE number IS NOT NULL;
CREATE UNIQUE INDEX ux_invoice_period ON invoice (client_id, period_start) WHERE status <> 'VOID';
CREATE INDEX ix_invoice_client_period ON invoice (client_id, period_start DESC);
CREATE INDEX ix_invoice_status_period ON invoice (status, period_start DESC);

CREATE TABLE invoice_line (
    id          UUID PRIMARY KEY,
    invoice_id  UUID          NOT NULL REFERENCES invoice (id) ON DELETE CASCADE,
    position    INT           NOT NULL,
    kind        VARCHAR(16)   NOT NULL,
    channel     VARCHAR(16),
    description VARCHAR(300)  NOT NULL,
    quantity    BIGINT        NOT NULL,
    unit_price  NUMERIC(19,6) NOT NULL,
    amount      NUMERIC(19,6) NOT NULL
);
CREATE INDEX ix_invoice_line_invoice ON invoice_line (invoice_id, position);

CREATE TABLE invoice_payment (
    id          UUID PRIMARY KEY,
    invoice_id  UUID          NOT NULL REFERENCES invoice (id) ON DELETE CASCADE,
    amount      NUMERIC(19,6) NOT NULL,
    method      VARCHAR(40)   NOT NULL,
    reference   VARCHAR(120)  NOT NULL,
    received_at TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_payment_amount CHECK (amount > 0)
);
CREATE UNIQUE INDEX ux_payment_reference ON invoice_payment (invoice_id, reference);

CREATE INDEX ix_message_sent_usage ON notification_message (client_id, channel, sent_at) WHERE status = 'SENT';
--rollback DROP INDEX ix_message_sent_usage;
--rollback DROP TABLE invoice_payment;
--rollback DROP TABLE invoice_line;
--rollback DROP TABLE invoice;
--rollback DROP SEQUENCE invoice_number_seq;
--rollback DROP TABLE credit_hold;
--rollback DROP TABLE credit_ledger_entry;
--rollback DROP TABLE billing_account;
--rollback DROP TABLE billing_plan_rate;
--rollback DROP TABLE billing_plan;
