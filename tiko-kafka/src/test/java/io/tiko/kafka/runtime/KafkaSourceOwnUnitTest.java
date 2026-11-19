package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import io.tiko.kafka.KafkaConfig;
import io.tiko.kafka.KafkaIngestError;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.runtime.Tiko;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #681: a source may open the unit of work of each record itself. A Kotlin {@code suspend} bridge
 * does: it runs as a coroutine on the consumer thread, in a unit that travels with the coroutine.
 * The runner then opens none, commits once the dispatcher has returned, and cancels a dispatcher
 * that outlasts the shutdown timeout by interrupting the consumer thread.
 *
 * <p>The dispatcher here is Java and stands in for the generated call of a {@code suspend} bridge:
 * it opens a unit, may wait, publishes its event inside the unit and returns {@code null}.
 */
class KafkaSourceOwnUnitTest {

    private static final String TOPIC = "own-unit";
    private static final TopicPartition P0 = new TopicPartition(TOPIC, 0);

    /** Counts closes, tells when {@code stop()} woke it, and can fail a commit the way a real client does. */
    private static final class Client extends ScriptedConsumerClient {
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch wokenUp = new CountDownLatch(1);
        volatile boolean interruptedCommits;

        Client() {
            super(List.of(new ConsumerRecords<>(
                    Map.of(P0, List.of(RunnerTestSupport.consumerRecord(TOPIC, 0, 0, "a record"))))));
        }

        @Override
        public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            if (interruptedCommits) {
                awaitUninterruptibly(wokenUp);
                // What KafkaConsumer.commitSync throws on an interrupted thread; it sets the flag again.
                throw new InterruptException(new InterruptedException("interrupted in commitSync"));
            }
            super.commitSync(offsets);
        }

        @Override
        public void wakeup() {
            super.wakeup();
            wokenUp.countDown();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private final Client client = new Client();
    private final List<ErrorContext> errors = new CopyOnWriteArrayList<>();
    private final List<String> unitsStarted = new CopyOnWriteArrayList<>();
    private final List<String> unitsEnded = new CopyOnWriteArrayList<>();
    private final List<String> published = new CopyOnWriteArrayList<>();

    /** The dispatcher has opened its unit and is about to wait. */
    private final CountDownLatch inTheUnit = new CountDownLatch(1);

    /** What a waiting dispatcher waits for. */
    private final CountDownLatch release = new CountDownLatch(1);

    private final AtomicReference<Thread> consumerThread = new AtomicReference<>();
    private final AtomicInteger dispatches = new AtomicInteger();
    private final AtomicInteger interrupts = new AtomicInteger();

    private Container container;
    private ThreadPerTopicRunner runner;

    @BeforeEach
    void boot() {
        container = Tiko.create();
        container.getEventBus().subscribe(EventStartedEvent.class, e -> unitsStarted.add(e.eventId()));
        container.getEventBus().subscribe(EventEndingEvent.class, e -> unitsEnded.add(e.eventId()));
        container.getEventBus().subscribe(String.class, published::add);
    }

    @AfterEach
    void close() {
        release.countDown();
        if (runner != null) {
            runner.stop();
        }
        container.close();
    }

    /** A dispatcher that opens its own unit, runs {@code inUnit} there, publishes and returns null. */
    private GeneratedSourceDescriptor ownUnitSource(Runnable inUnit) {
        return new GeneratedSourceDescriptor(
                TOPIC,
                "",
                "StringEvent",
                String.class,
                KafkaSerializer.Default.class,
                false,
                (c, payload, ctx) -> {
                    consumerThread.set(Thread.currentThread());
                    dispatches.incrementAndGet();
                    c.runInEventScope(() -> {
                        inUnit.run();
                        c.getEventBus().publish("event of " + payload);
                    });
                    return null;
                },
                true);
    }

    /** Waits for {@link #release} as a suspended bridge waits: an interrupt ends the wait and is thrown on. */
    private void waitForRelease() {
        inTheUnit.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            interrupts.incrementAndGet();
            throw sneaky(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        var interrupted = false;
        while (true) {
            try {
                if (latch.await(10, TimeUnit.SECONDS)) {
                    break;
                }
                throw new AssertionError("stop() never woke the consumer");
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void start(GeneratedSourceDescriptor source, Duration shutdownTimeout) {
        var config = new KafkaConfig(
                "unused:9092",
                "g",
                "json",
                "earliest",
                Duration.ofMillis(10),
                shutdownTimeout,
                Map.of(),
                Map.of(),
                "SEEK",
                Duration.ZERO,
                Duration.ZERO);
        runner = new ThreadPerTopicRunner(
                source, client, container, container.getEventBus(), errors::add, RunnerTestSupport.UTF8, config);
        runner.start();
    }

    private void awaitCommit() {
        await().atMost(Duration.ofSeconds(5)).until(() -> !client.commits.isEmpty());
    }

    @Test
    void theRunnerOpensNoUnitForASourceThatOpensItsOwn() {
        start(ownUnitSource(() -> {}), Duration.ofSeconds(2));
        awaitCommit();

        assertThat(unitsStarted)
                .as("one unit of work for the record, the dispatcher's")
                .hasSize(1);
        assertThat(unitsEnded).containsExactlyElementsOf(unitsStarted);
        assertThat(published).containsExactly("event of a record");
        assertThat(client.commits).containsExactly(Map.entry(P0, 1L));
        assertThat(errors).isEmpty();
    }

    @Test
    void theOffsetIsCommittedOnlyAfterTheDispatcherReturned() throws InterruptedException {
        start(ownUnitSource(this::waitForRelease), Duration.ofSeconds(2));
        assertThat(inTheUnit.await(5, TimeUnit.SECONDS)).isTrue();
        var committedWhileItWaited = List.copyOf(client.commits);

        release.countDown();
        awaitCommit();

        assertThat(committedWhileItWaited).isEmpty();
        assertThat(client.commits).containsExactly(Map.entry(P0, 1L));
    }

    @Test
    void aFailureTakesTheIngestErrorPath() {
        start(
                ownUnitSource(() -> {
                    throw new IllegalStateException("the bridge failed");
                }),
                Duration.ofSeconds(2));
        await().atMost(Duration.ofSeconds(5)).until(() -> !errors.isEmpty());

        assertThat(errors).singleElement().isInstanceOfSatisfying(KafkaIngestError.class, error -> {
            assertThat(error.cause()).hasMessage("the bridge failed");
            assertThat(error.offset()).isZero();
        });
        assertThat(client.seeks).as("SEEK: delivered again").containsExactly(Map.entry(P0, 0L));
        assertThat(client.commits).isEmpty();
        assertThat(unitsEnded).as("the unit of the failed record closed").hasSize(1);
    }

    @Test
    void stopWaitsForADispatcherThatFinishesInTime() throws InterruptedException {
        start(ownUnitSource(this::waitForRelease), Duration.ofSeconds(5));
        assertThat(inTheUnit.await(5, TimeUnit.SECONDS)).isTrue();

        var stopper = new Thread(runner::stop);
        stopper.start();
        assertThat(client.wokenUp.await(5, TimeUnit.SECONDS)).as("stop() began").isTrue();
        release.countDown();
        stopper.join(5_000);

        assertThat(stopper.isAlive()).isFalse();
        assertThat(interrupts).as("it finished in time: not interrupted").hasValue(0);
        assertThat(client.commits).as("and its record is committed").containsExactly(Map.entry(P0, 1L));
        assertThat(consumerThread.get().isAlive()).isFalse();
        assertThat(errors).isEmpty();
    }

    @Test
    void stopInterruptsADispatcherThatOutlastsTheShutdownTimeout() throws InterruptedException {
        start(ownUnitSource(this::waitForRelease), Duration.ofMillis(200));
        assertThat(inTheUnit.await(5, TimeUnit.SECONDS)).isTrue();

        runner.stop();

        assertThat(interrupts)
                .as("cancelled by an interrupt of the consumer thread")
                .hasValue(1);
        assertThat(consumerThread.get().isAlive())
                .as("no consumer thread is left when stop() returns")
                .isFalse();
        assertThat(client.commits).as("the record stays uncommitted").isEmpty();
        assertThat(errors).as("an orderly stop, not an ingest failure").isEmpty();
        assertThat(unitsEnded).as("its unit closed").containsExactlyElementsOf(unitsStarted);
        assertThat(published).isEmpty();
        assertThat(client.closes).hasValue(1);
    }

    /** The interrupt can land after the bridge returned: the commit then fails as an interrupted one. */
    @Test
    void anInterruptedCommitOfAStoppingRunnerIsAnOrderlyStop() {
        client.interruptedCommits = true;
        start(ownUnitSource(() -> {}), Duration.ofSeconds(2));
        await().atMost(Duration.ofSeconds(5)).until(() -> dispatches.get() == 1);

        runner.stop();

        assertThat(consumerThread.get().isAlive()).isFalse();
        assertThat(errors).as("not reported as an ingest failure").isEmpty();
        assertThat(client.seeks).isEmpty();
        assertThat(client.closes).as("the consumer is closed").hasValue(1);
    }

    /** A bootstrap generated by an earlier processor builds its sources without the new component. */
    @Test
    void aSourceBuiltWithTheSevenArgumentConstructorOpensNoUnitOfItsOwn() {
        var source = RunnerTestSupport.stringSource(TOPIC, payload -> payload);
        start(source, Duration.ofSeconds(2));
        awaitCommit();

        assertThat(source.opensItsUnit()).isFalse();
        assertThat(unitsStarted).as("the runner opened the record's unit").hasSize(1);
        assertThat(published).containsExactly("a record");
    }
}
