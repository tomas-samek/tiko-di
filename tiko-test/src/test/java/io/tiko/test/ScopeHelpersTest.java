package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import org.junit.jupiter.api.Test;

@TikoTest
class ScopeHelpersTest {

    @Test
    @RequestScopeTest
    void runsInsideRequestScope(Container c) {
        // A REQUEST-scoped component must be resolvable here — outside request scope it would throw.
        assertThat(c.get(io.tiko.test.fixtures.RequestScopedService.class)).isNotNull();
    }

    @Test
    @EventScopeTest
    void runsInsideEventScope(Container c) {
        assertThat(c.get(io.tiko.test.fixtures.EventScopedService.class)).isNotNull();
    }
}
