package com.techeazy.notification.domain;

public enum RateLimitScope {
    /** Requests per second a client may make against the Client API. */
    CLIENT_API,
    /** Messages per second a client may have delivered on a channel. */
    CLIENT_CHANNEL,
    /** Messages per second the platform sends on a channel (protects the downstream provider). */
    GLOBAL_CHANNEL
}
