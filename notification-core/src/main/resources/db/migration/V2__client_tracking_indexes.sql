-- Client-facing tracking: "my recent requests" and "my message counts over a window" both filter by client and time.
CREATE INDEX ix_request_client_created ON notification_request (client_id, created_at DESC);
CREATE INDEX ix_message_client_created ON notification_message (client_id, created_at);
