package io.tiko.examples.basic.teardown;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutoCloseableScopedTest {

    @BeforeEach
    void resetRecorder() {
        TeardownRecorder.reset();
    }

    @Test
    void singleton_autocloseable_is_closed_at_shutdown() {
        Container container = Tiko.create();
        // AutoCloseableSingletonHolder is a SINGLETON @Component, so eagerly created in start().
        container.shutdown();

        assertThat(TeardownRecorder.order).contains("AutoCloseableSingleton");
    }

    @Test
    void request_autocloseable_is_closed_at_scope_exit() {
        Container container = Tiko.create();
        try {
            container.runInEventScope(() -> container.get(AutoCloseableRequestHolder.class));
        } finally {
            container.shutdown();
        }

        assertThat(TeardownRecorder.order).contains("AutoCloseableRequest");
    }

    @Test
    void event_autocloseable_is_closed_at_scope_exit() {
        Container container = Tiko.create();
        try {
            container.runInEventScope(() -> container.get(AutoCloseableEventHolder.class));
        } finally {
            container.shutdown();
        }

        assertThat(TeardownRecorder.order).contains("AutoCloseableEvent");
    }

    @Test
    void explicit_predestroy_wins_over_autocloseable_close() {
        Container container = Tiko.create();
        // ExplicitWinsBean is a SINGLETON, eagerly created.
        container.shutdown();

        assertThat(TeardownRecorder.order)
                .contains("ExplicitWinsBean.preDestroy")
                .doesNotContain("ExplicitWinsBean.close");
    }
}
