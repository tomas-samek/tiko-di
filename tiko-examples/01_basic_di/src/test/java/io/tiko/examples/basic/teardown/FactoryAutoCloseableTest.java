package io.tiko.examples.basic.teardown;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FactoryAutoCloseableTest {

    @BeforeEach
    void resetRecorder() {
        TeardownRecorder.reset();
    }

    @Test
    void singleton_factory_produced_autocloseable_is_closed_at_shutdown() {
        Container container = Tiko.create();
        // Touch the factory-produced bean so it lands in the singleton cache.
        FakePool primary = container.get(FakePool.class, "primary");
        assertThat(primary.label()).isEqualTo("primary");

        container.shutdown();

        assertThat(TeardownRecorder.order)
                .as("@Produces returning AutoCloseable must auto-close at shutdown")
                .contains("FakePool.primary");
    }

    @Test
    void request_factory_produced_autocloseable_is_closed_at_scope_exit() {
        Container container = Tiko.create();
        try {
            container.runInRequestScope(() -> {
                FakePool pool = container.get(FakePool.class);
                assertThat(pool.label()).isEqualTo("request");
            });
        } finally {
            container.shutdown();
        }

        assertThat(TeardownRecorder.order)
                .as("@Produces returning AutoCloseable in REQUEST scope must auto-close at scope exit")
                .contains("FakePool.request");
    }

    @Test
    void factory_produced_bean_not_touched_in_scope_is_not_closed() {
        Container container = Tiko.create();
        try {
            container.runInRequestScope(() -> {
                // Don't touch the request-scoped FakePool — nothing to close.
            });
        } finally {
            container.shutdown();
        }

        assertThat(TeardownRecorder.order)
                .as("Lazy factory beans never created in a scope must not appear in teardown")
                .doesNotContain("FakePool.request");
    }
}
