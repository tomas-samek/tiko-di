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
}
