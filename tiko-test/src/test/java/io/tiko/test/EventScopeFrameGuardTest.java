package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.NoActiveEventScopeException;
import io.tiko.test.fixtures.EventScopedService;
import io.tiko.test.fixtures.RequestContextHolder;
import org.junit.jupiter.api.Test;

/**
 * Pins the unit-of-work frame guard on EVENT-scoped bean resolution (#302).
 *
 * <p>Resolving an EVENT-scoped bean while no {@code runInEventScope} /
 * {@code supplyInEventScope} frame is open must raise {@link NoActiveEventScopeException}
 * instead of silently materializing an instance that no teardown path will ever drain —
 * whether the resolution comes from a direct {@code container.get(...)} or from a method
 * call on a cross-scope proxy held by a SINGLETON.
 */
@TikoTest
class EventScopeFrameGuardTest {

    @Test
    void directGetOutsideUnitOfWorkThrows(Container c) {
        assertThatThrownBy(() -> c.get(EventScopedService.class))
                .isInstanceOf(NoActiveEventScopeException.class)
                .hasMessageContaining(EventScopedService.class.getName())
                .hasMessageContaining("runInEventScope");
    }

    @Test
    void proxiedCallOutsideUnitOfWorkThrows(Container c) {
        var holder = c.get(RequestContextHolder.class);
        assertThatThrownBy(holder::currentRequestId).isInstanceOf(NoActiveEventScopeException.class);
    }

    @Test
    void proxiedCallInsideUnitOfWorkResolves(Container c) {
        var holder = c.get(RequestContextHolder.class);
        var requestId = c.supplyInEventScope(holder::currentRequestId);
        assertThat(requestId).isNotEmpty();
    }

    @Test
    void eachUnitOfWorkGetsItsOwnInstance(Container c) {
        var holder = c.get(RequestContextHolder.class);
        var first = c.supplyInEventScope(holder::currentRequestId);
        var second = c.supplyInEventScope(holder::currentRequestId);
        assertThat(second).isNotEqualTo(first);
    }
}
