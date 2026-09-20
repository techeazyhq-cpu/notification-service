package com.techeazy.notification.dispatcher;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter @Setter
@ConfigurationProperties(prefix = "dispatcher")
public class DispatcherProperties {
    /** Parallel Pulsar consumers per channel (Shared subscription spreads messages across them). */
    private int consumersPerChannel = 2;
    /** Total send attempts before a message is marked FAILED. */
    private int maxAttempts = 5;
    /** Retry delay = base * 2^(attempt-1), capped at maxBackoffSeconds. */
    private long baseBackoffSeconds = 5;
    private long maxBackoffSeconds = 300;
    /** How long a worker blocks waiting for a rate-limit token before handing the message back. */
    private long maxRateLimitWaitSeconds = 60;
}
