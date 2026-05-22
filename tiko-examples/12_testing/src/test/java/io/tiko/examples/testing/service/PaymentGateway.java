package io.tiko.examples.testing.service;

public interface PaymentGateway {
    String charge(String customerId, long amountCents);
}
