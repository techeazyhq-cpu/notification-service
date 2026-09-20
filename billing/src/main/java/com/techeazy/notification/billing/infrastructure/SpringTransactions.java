package com.techeazy.notification.billing.infrastructure;

import com.techeazy.notification.billing.application.port.Transactions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

class SpringTransactions implements Transactions {

    private final TransactionTemplate template;

    SpringTransactions(TransactionTemplate template) {
        this.template = template;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
