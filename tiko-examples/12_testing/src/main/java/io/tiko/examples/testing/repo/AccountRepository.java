package io.tiko.examples.testing.repo;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(scope = Scope.EVENT)
public class AccountRepository {
    public String findCustomerName(String customerId) {
        return "Customer-" + customerId;
    }
}
