package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.test.fixtures.EventScopedService;
import org.junit.jupiter.api.Test;

@TikoTest
class ScopeHelpersTest {

    @Test
    @EventScopeTest
    void eventScopedBeanResolvableInsideEventScope(Container c) {
        assertThat(c.get(EventScopedService.class)).isNotNull();
    }
}
