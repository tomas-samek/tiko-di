package io.tiko.examples.testing.repo;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.test.EventScopeTest;
import io.tiko.test.TikoTest;
import org.junit.jupiter.api.Test;

@TikoTest
class RequestScopedRepoTest {

    @Test
    @EventScopeTest
    void requestScopedRepoResolvableInsideScopeWrapper(AccountRepository repo) {
        assertThat(repo.findCustomerName("alice")).isEqualTo("Customer-alice");
    }
}
