--liquibase formatted sql

--changeset notification:002-client-tracking-indexes
--comment "My recent requests" and "my message counts over a window" both filter by client and time (formerly Flyway V2).
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema() AND indexname = 'ix_request_client_created'
CREATE INDEX ix_request_client_created ON notification_request (client_id, created_at DESC);
CREATE INDEX ix_message_client_created ON notification_message (client_id, created_at);
--rollback DROP INDEX ix_message_client_created;
--rollback DROP INDEX ix_request_client_created;
