package io.tiko;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventDispatchRejectedTest {

    @Test
    void carriesTheEventAndHasNullCause() {
        var event = new Object();
        EventDispatchRejected rejected = new EventDispatchRejected(event);

        assertThat(rejected.event()).isSameAs(event);
        assertThat(rejected.cause()).isNull();
        assertThat((ErrorContext) rejected).isInstanceOf(ErrorContext.class);
    }
}
