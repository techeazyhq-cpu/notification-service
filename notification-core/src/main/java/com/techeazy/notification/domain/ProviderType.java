package com.techeazy.notification.domain;

public enum ProviderType {
    /** SMTP server (Mailpit locally, any relay in production). */
    SMTP,
    /** Generic JSON-over-HTTP gateway; used for the SMS, WhatsApp and push catchers and as a base for real gateways. */
    HTTP_JSON
}
