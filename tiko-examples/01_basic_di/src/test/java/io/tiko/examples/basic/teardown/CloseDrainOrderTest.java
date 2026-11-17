package io.tiko.examples.basic.teardown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #721: {@code close()} waits for the async handlers that were queued or running when it began,
 * and only then tears the singletons down. Holds for the framework's event executor and for a
 * supplied one.
 */
class CloseDrainOrderTest {

    private static final String TORN_DOWN = "resource torn down";

    private final ExecutorService supplied = Executors.newFixedThreadPool(2);
    private final ExecutorService closer = Executors.newSingleThreadExecutor();
    private CountDownLatch gate;

    @BeforeEach
    void armTheHandler() {
        TeardownRecorder.reset();
        gate = new CountDownLatch(1);
        DrainingHandler.entered = new CountDownLatch(1);
        DrainingHandler.gate = gate;
    }

    @AfterEach
    void release() {
        gate.countDown();
        DrainingHandler.gate = new CountDownLatch(0);
        closer.shutdownNow();
        supplied.shutdownNow();
    }

    static Stream<Arguments> executors() {
        return Stream.of(Arguments.of("framework executor", false), Arguments.of("supplied executor", true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executors")
    void runningAsyncHandlerFinishesBeforeTheSingletonItUsesIsTornDown(String name, boolean suppliedExecutor)
            throws Exception {
        var options = TikoOptions.builder().shutdownTimeout(Duration.ofSeconds(10));
        if (suppliedExecutor) {
            options.eventExecutor(supplied);
        }
        Container container = Tiko.create(options.build());
        container.getEventBus().publish(new DrainPing());
        assertThat(DrainingHandler.entered.await(5, TimeUnit.SECONDS)).isTrue();

        var closing = closer.submit(container::close);

        // close() waits for the handler, and the resource stays up for as long as it runs.
        assertThatThrownBy(() -> closing.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        assertThat(TeardownRecorder.order).doesNotContain(TORN_DOWN);
        gate.countDown();
        closing.get(10, TimeUnit.SECONDS);

        assertThat(TeardownRecorder.order)
                .filteredOn(entry -> entry.startsWith("handler") || entry.equals(TORN_DOWN))
                .containsExactly("handler read the resource, closed=false", TORN_DOWN);
    }
}
