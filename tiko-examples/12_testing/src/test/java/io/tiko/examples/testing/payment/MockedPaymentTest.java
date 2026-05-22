package io.tiko.examples.testing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tiko.Container;
import io.tiko.examples.testing.service.OrderService;
import io.tiko.examples.testing.service.PaymentGateway;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates runtime override keyed by an interface: a Mockito mock of
 * {@link PaymentGateway} is registered via {@link TikoOptions.Builder#override(Class, java.util.function.Supplier)}
 * and the framework hands it to {@link OrderService} in place of the production
 * {@code HttpPaymentGateway}, without any change to the service or its component
 * declaration.
 */
class MockedPaymentTest {

    @Test
    void runtimeOverrideSwapsInAMockitoMockOfTheInterface() {
        PaymentGateway mock = mock(PaymentGateway.class);
        when(mock.charge(anyString(), anyLong())).thenReturn("MOCK-TXN");

        try (Container c = Tiko.create(
                TikoOptions.builder().override(PaymentGateway.class, () -> mock).build())) {
            String txn = c.get(OrderService.class).create("alice", 100L);
            assertThat(txn).isEqualTo("MOCK-TXN");
            verify(mock).charge("alice", 100L);
        }
    }
}
