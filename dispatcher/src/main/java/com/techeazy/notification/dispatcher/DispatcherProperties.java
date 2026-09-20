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
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    /** One breaker per provider config, so a dead SMTP relay never blocks a healthy backup provider. */
    @Getter @Setter
    public static class CircuitBreaker {
        private boolean enabled = true;
        /** Number of most recent calls the failure rate is computed over. */
        private int slidingWindowSize = 20;
        /** Failure rate is not evaluated until this many calls were recorded. */
        private int minimumNumberOfCalls = 10;
        /** Percentage of transient failures that opens the circuit. */
        private float failureRateThreshold = 50;
        /** Calls slower than this count as slow (a hung provider is as bad as a failing one). */
        private long slowCallDurationMs = 8000;
        private float slowCallRateThreshold = 80;
        /** How long the circuit stays open before probing the provider again. */
        private long waitDurationInOpenStateMs = 30_000;
        /** Probe calls allowed while half-open; all must go well enough to close the circuit. */
        private int permittedCallsInHalfOpenState = 3;
    }
}
