package io.tiko.examples.testing.service;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(scope = Scope.SINGLETON)
public class HttpPaymentGateway implements PaymentGateway {
    @Override
    public String charge(String customerId, long amountCents) {
        // Pretend HTTP call.
        return "txn-" + customerId + "-" + amountCents;
    }
}
