package io.tiko.processor.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StackTracesTest {

    @Test
    void formatsCauseChainOnIndentedLines() {
        var error = new RuntimeException("outer", new IllegalStateException("inner"));

        var text = StackTraces.format(error);

        assertThat(text)
                .startsWith("java.lang.RuntimeException: outer")
                .contains("\n  at ")
                .contains("\nCaused by: java.lang.IllegalStateException: inner");
    }

    @Test
    void omitsSeparatorForNullMessage() {
        var text = StackTraces.format(new IllegalStateException());

        assertThat(text).startsWith("java.lang.IllegalStateException\n  at ").doesNotContain("null");
    }

    @Test
    void doesNotRecurseIntoSelfReferentialCause() {
        var selfCaused = new SelfCaused("loop");

        var text = StackTraces.format(selfCaused);

        assertThat(text).startsWith(SelfCaused.class.getName() + ": loop").doesNotContain("Caused by");
    }

    /** {@code getCause()} returning the throwable itself, as some legacy exceptions do. */
    private static final class SelfCaused extends RuntimeException {

        SelfCaused(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
