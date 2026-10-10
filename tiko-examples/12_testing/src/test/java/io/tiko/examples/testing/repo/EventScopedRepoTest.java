package io.tiko.examples.testing.repo;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.test.EventScopeTest;
import io.tiko.test.TikoTest;
import org.junit.jupiter.api.Test;

@TikoTest
class RequestScopedRepoTest {

    // EVENT-scoped beans resolve inside the scope wrapper, not as method parameters:
    // JUnit resolves parameters before the @EventScopeTest frame opens, and resolving an
    // EVENT bean outside a unit of work throws NoActiveEventScopeException (#302).
    @Test
    @EventScopeTest
    void requestScopedRepoResolvableInsideScopeWrapper(Container container) {
        var repo = container.get(AccountRepository.class);
        assertThat(repo.findCustomerName("alice")).isEqualTo("Customer-alice");
    }
}
