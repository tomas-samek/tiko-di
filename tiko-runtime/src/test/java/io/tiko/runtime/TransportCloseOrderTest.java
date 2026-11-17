package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.TransportBootstrap;
import io.tiko.events.ApplicationEndingEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #721: closing tells the transports to stop taking work in first, and shuts them down only once
 * the container has finished its async work, so that work can still send through them.
 */
class TransportCloseOrderTest {

    private static final ErrorHandler NOOP_ERROR_HANDLER = ctx -> {};
    private static final String STOP_INGEST = "transport stops taking work in";
    private static final String SHUTDOWN = "transport shuts down";
    private static final String CONTAINER = "container shuts down";
    private static final String ENDING = "ending event";
    private static final String WORK_DONE = "async work finishes";

    private final List<String> order = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clearLog() {
        CapturingLoggerFinder.clear();
    }

    private class RecordingTransport implements TransportBootstrap {
        @Override
        public void start(Container container) {
            // started successfully
        }

        @Override
        public void stopIngest() {
            order.add(STOP_INGEST);
        }

        @Override
        public void shutdown() {
            order.add(SHUTDOWN);
        }
    }

    /** A container that is not Tiko's own, or one generated before 0.8.0: it never asks. */
    @Test
    void containerThatNeverAsksHasItsTransportsShutDownAfterIt() {
        var container = new BootstrapTestContainer() {
            @Override
            public void shutdown() {
                order.add(CONTAINER);
            }
        };
        Container handle = Tiko.startTransports(container, List.of(new RecordingTransport()));

        handle.close();
        handle.close();

        assertThat(order)
                .as("a second close() reaches the container again and the transports no more")
                .containsExactly(STOP_INGEST, CONTAINER, SHUTDOWN, CONTAINER);
    }

    @Test
    void closingContainerShutsItsTransportsDownOnceItsAsyncWorkHasFinished() throws Exception {
        var aggregate = new AggregatingContainer(
                new LocalEventBus(), NOOP_ERROR_HANDLER, /* userEventExecutor= */ null, Duration.ofSeconds(10));
        aggregate.getEventBus().subscribe(ApplicationEndingEvent.class, ending -> order.add(ENDING));
        Container handle = Tiko.startTransports(aggregate, List.of(new RecordingTransport()));
        var started = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        aggregate.getEventExecutor().execute(() -> {
            started.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            order.add(WORK_DONE);
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        // Closed in reverse: the gate opens first, so a failed assertion cannot leave close() waiting.
        try (var closer = Executors.newSingleThreadExecutor();
                AutoCloseable release = gate::countDown) {
            var closing = closer.submit(handle::close);

            assertThatThrownBy(() -> closing.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(order).containsExactly(STOP_INGEST, ENDING);

            gate.countDown();
            closing.get(10, TimeUnit.SECONDS);
        }

        assertThat(order).containsExactly(STOP_INGEST, ENDING, WORK_DONE, SHUTDOWN);
    }

    @Test
    void transportThatFailsToStopIngestIsLoggedAndTheRestStillCloses() {
        var container = new BootstrapTestContainer();
        TransportBootstrap failing = new RecordingTransport() {
            @Override
            public void stopIngest() {
                throw new IllegalStateException("stop ingest boom");
            }
        };
        Container handle = Tiko.startTransports(container, List.of(failing, new RecordingTransport()));

        handle.close();

        assertThat(container.shutdowns.get()).isEqualTo(1);
        assertThat(order).containsExactly(STOP_INGEST, SHUTDOWN, SHUTDOWN);
        assertThat(CapturingLoggerFinder.RECORDS)
                .filteredOn(entry -> entry.level() == System.Logger.Level.WARNING)
                .filteredOn(entry -> entry.message().contains("failed to stop taking work in"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.thrown()).hasMessage("stop ingest boom"));
    }

    /** An {@link Error} from {@code stopIngest()} is rethrown, as one from {@code shutdown()} is (#737). */
    @Test
    void errorFromStopIngestIsRethrownAfterTheContainerHasShutDown() {
        var container = new BootstrapTestContainer();
        TransportBootstrap failing = new RecordingTransport() {
            @Override
            public void stopIngest() {
                throw new AssertionError("stop ingest error");
            }
        };
        Container handle = Tiko.startTransports(container, List.of(failing));

        assertThatThrownBy(handle::close).isInstanceOf(AssertionError.class).hasMessage("stop ingest error");

        assertThat(container.shutdowns.get()).isEqualTo(1);
        assertThat(order).containsExactly(SHUTDOWN);
    }

    @Test
    void optionsOfABootWithoutTransportsCloseNothing() {
        var options = TikoOptions.builder().build();

        assertThatCode(options::closeTransports).doesNotThrowAnyException();
    }
}
