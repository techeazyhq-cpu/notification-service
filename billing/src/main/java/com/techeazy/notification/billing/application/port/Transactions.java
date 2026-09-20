package com.techeazy.notification.billing.application.port;

import java.util.function.Supplier;

/** Runs work atomically, joining the caller's transaction when there is one. */
public interface Transactions {

    <T> T inTransaction(Supplier<T> work);

    default void inTransaction(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }
}
