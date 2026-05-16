package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * Verifies the sneaky-throw helper rethrows the original throwable instance
 * (identity, not just type) without adding any wrapping frames.
 */
class UncheckedTest {

    @Test
    void sneakyThrowRethrowsOriginalCheckedException() {
        SQLException original = new SQLException("nope");
        try {
            Unchecked.<RuntimeException>sneakyThrow(original);
            fail("sneakyThrow should have thrown");
        } catch (Throwable t) {
            assertThat(t).isSameAs(original);
        }
    }

    @Test
    void sneakyThrowRethrowsOriginalRuntimeException() {
        IllegalStateException original = new IllegalStateException("nope");
        try {
            Unchecked.<RuntimeException>sneakyThrow(original);
            fail("sneakyThrow should have thrown");
        } catch (RuntimeException t) {
            assertThat(t).isSameAs(original);
        }
    }
}
