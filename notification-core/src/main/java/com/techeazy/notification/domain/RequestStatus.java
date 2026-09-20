package com.techeazy.notification.domain;

/** Derived from the statuses of the messages in a request; never stored. */
public enum RequestStatus {
    PROCESSING, COMPLETED, PARTIALLY_FAILED, FAILED;

    public static RequestStatus derive(long total, long inFlight, long sent, long failed) {
        if (inFlight > 0 || sent + failed < total) return PROCESSING;
        if (failed == 0) return COMPLETED;
        if (sent == 0) return FAILED;
        return PARTIALLY_FAILED;
    }
}
