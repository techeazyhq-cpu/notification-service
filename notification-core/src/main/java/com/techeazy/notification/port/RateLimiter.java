package com.techeazy.notification.port;

/** Distributed token-bucket limiter. */
public interface RateLimiter {

    /** @param waitMillis when denied, how long until enough tokens exist (a hint, not a reservation) */
    record Decision(boolean allowed, long waitMillis) {
        public static final Decision ALLOWED = new Decision(true, 0);
    }

    Decision tryAcquire(String key, double ratePerSecond, int burst);
}
