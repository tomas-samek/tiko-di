package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Event;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * #677: a unit of work that is not bound to one {@code Runnable}. It is put on a thread, taken
 * off, put on another one, and closed exactly once.
 */
class CarriedUnitTest {

    /** A module's container reduced to its EVENT frame: a thread-local, as in a generated container. */
    private static class FrameModule implements DetachedUnitFrame {
        private final String name;
        private final List<String> log;
        private final ThreadLocal<UnitFrame> current =
                ThreadLocal.withInitial(() -> new UnitFrame(new LinkedHashMap<>(), false));
        private RuntimeException closeFailure;

        FrameModule(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void runInDetachedFrame(Runnable task, boolean publishLifecycle) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UnitFrame swapFrame(UnitFrame frame) {
            var previous = current.get();
            current.set(frame);
            return previous;
        }

        @Override
        public void closeFrame() {
            var frame = current.get();
            log.add(name + " closes " + frame.scope().keySet());
            frame.scope().clear();
            current.set(new UnitFrame(frame.scope(), false));
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        UnitFrame frame() {
            return current.get();
        }

        void put(String key) {
            current.get().scope().put(key, key);
        }
    }

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final FrameModule a = new FrameModule("a", log);
    private final FrameModule b = new FrameModule("b", log);

    private CarriedUnit unit(Event<?> origin) {
        return new CarriedUnit(List.of(a, b), origin, event -> log.add(describe(event)));
    }

    private static String describe(Object event) {
        return switch (event) {
            case EventStartedEvent started -> "started " + started.eventId();
            case EventEndingEvent ending -> "ending " + ending.eventId();
            default -> throw new AssertionError(event);
        };
    }

    /** Runs {@code body} on a thread of its own and waits for it; a failure there fails the test. */
    private static void onAnotherThread(Runnable body) throws InterruptedException {
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(body, "another-thread");
        thread.setUncaughtExceptionHandler((ignored, thrown) -> failure.set(thrown));
        thread.start();
        thread.join();
        assertThat(failure.get()).as("failure on the other thread").isNull();
    }

    private static Event<?> currentOrigin() {
        Event<?> current = EventChainContext.enter(null);
        EventChainContext.exit(current);
        return current;
    }

    @Test
    void installPutsEveryModulesFrameOnTheThreadAndRestorePutsBackWhatWasThere() {
        var before = List.of(a.frame(), b.frame());

        var unit = unit(null);
        var displaced = unit.install();
        var installed = List.of(a.frame(), b.frame());
        unit.restore(displaced);

        assertThat(installed).as("an open frame per module").allMatch(UnitFrame::open);
        assertThat(installed.get(0).scope())
                .as("each module has a frame of its own")
                .isNotSameAs(installed.get(1).scope());
        assertThat(List.of(a.frame(), b.frame())).as("what the thread had").isEqualTo(before);
    }

    @Test
    void theSameFramesAreInstalledOnAnotherThread() throws InterruptedException {
        var unit = unit(null);
        var displaced = unit.install();
        a.put("made on the first thread");
        unit.restore(displaced);

        var seen = new AtomicReference<Object>();
        onAnotherThread(() -> {
            var there = unit.install();
            seen.set(List.copyOf(a.frame().scope().keySet()));
            unit.restore(there);
        });

        assertThat(seen.get()).isEqualTo(List.of("made on the first thread"));
        assertThat(a.frame().scope())
                .as("the first thread no longer has the unit")
                .isEmpty();
    }

    @Test
    void aThreadWithAFrameOfItsOwnGetsItBackAfterRestore() {
        a.swapFrame(UnitFrame.opened());
        a.put("the thread's own");

        var unit = unit(null);
        var displaced = unit.install();
        var whileInstalled = List.copyOf(a.frame().scope().keySet());
        unit.restore(displaced);

        assertThat(whileInstalled).as("the carried unit starts empty").isEmpty();
        assertThat(a.frame().scope()).containsOnlyKeys("the thread's own");
        assertThat(a.frame().open()).isTrue();
    }

    @Test
    void theOriginIsCurrentWhileInstalledAndThePreviousOneIsBackAfterRestore() {
        Event<String> origin = EventChainContext.wrap("the unit's origin");
        Event<String> outer = EventChainContext.wrap("what the thread was handling");
        var previous = EventChainContext.enter(outer);
        try {
            var unit = unit(origin);
            var displaced = unit.install();
            var whileInstalled = currentOrigin();
            unit.restore(displaced);

            assertThat(whileInstalled).isSameAs(origin);
            assertThat(currentOrigin()).isSameAs(outer);
        } finally {
            EventChainContext.exit(previous);
        }
    }

    @Test
    void installingAgainOnTheThreadThatHoldsTheUnitChangesNothing() {
        var unit = unit(null);
        var outer = unit.install();
        a.put("mine");

        var inner = unit.install();
        var whileNested = List.copyOf(a.frame().scope().keySet());
        unit.restore(inner);
        var afterInnerRestore = List.copyOf(a.frame().scope().keySet());
        unit.restore(outer);

        assertThat(whileNested).containsExactly("mine");
        assertThat(afterInnerRestore)
                .as("still installed after the inner restore")
                .containsExactly("mine");
        assertThat(a.frame().scope()).as("taken off by the outer restore").isEmpty();
    }

    @Test
    void theFirstInstallPublishesTheStartedEventAndLaterInstallsPublishNothing() {
        var unit = unit(null);

        unit.restore(unit.install());
        unit.restore(unit.install());

        assertThat(log).hasSize(1).first().asString().startsWith("started ");
    }

    @Test
    void closeWhileInstalledPublishesTheEndingEventThenClosesFramesInReverseModuleOrder() {
        var unit = unit(null);
        var displaced = unit.install();
        a.put("x");
        b.put("y");

        unit.close();
        unit.restore(displaced);

        String id = log.get(0).substring("started ".length());
        assertThat(log).containsExactly("started " + id, "ending " + id, "b closes [y]", "a closes [x]");
        assertThat(unit.isClosed()).isTrue();
    }

    @Test
    void closeFromAThreadTheUnitIsNotOnInstallsItForTheTeardownAndRestores() throws InterruptedException {
        var unit = unit(null);
        var displaced = unit.install();
        a.put("x");
        unit.restore(displaced);

        onAnotherThread(() -> {
            a.swapFrame(UnitFrame.opened());
            a.put("the closing thread's own");
            unit.close();
            assertThat(a.frame().scope()).containsOnlyKeys("the closing thread's own");
        });

        assertThat(log).contains("a closes [x]");
        assertThat(log.indexOf("b closes []")).isLessThan(log.indexOf("a closes [x]"));
    }

    @Test
    void closeIsDoneOnce() {
        var unit = unit(null);
        unit.restore(unit.install());

        unit.close();
        var afterFirst = List.copyOf(log);
        unit.close();

        assertThat(log).isEqualTo(afterFirst);
    }

    @Test
    void aUnitThatWasNeverInstalledClosesWithoutEventsOrTeardown() {
        var unit = unit(null);

        unit.close();

        assertThat(log).isEmpty();
        assertThat(unit.isClosed()).isTrue();
    }

    @Test
    void installAfterCloseIsRejected() {
        var unit = unit(null);
        unit.close();

        assertThatThrownBy(unit::install)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void installOnASecondThreadWhileTheFirstHoldsTheUnitIsRejected() throws InterruptedException {
        var unit = unit(null);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = new Thread(
                () -> {
                    var displaced = unit.install();
                    a.put("the holder's");
                    held.countDown();
                    awaitQuietly(release);
                    unit.restore(displaced);
                },
                "holding-thread");
        holder.start();
        held.await();
        try {
            assertThatThrownBy(unit::install)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("holding-thread");
            assertThat(a.frame().scope())
                    .as("nothing was put on the second thread")
                    .isEmpty();
        } finally {
            release.countDown();
            holder.join();
        }
    }

    /** A unit always closes (SEC-14): the thread that has it tears it down when it takes it off. */
    @Test
    void closeWhileAnotherThreadHoldsTheUnitIsDoneByThatThreadWhenItTakesTheUnitOff() throws InterruptedException {
        var unit = unit(null);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = new Thread(
                () -> {
                    var displaced = unit.install();
                    a.put("the holder's");
                    held.countDown();
                    awaitQuietly(release);
                    unit.restore(displaced);
                },
                "holding-thread");
        holder.start();
        held.await();

        unit.close();
        var whileHeld = List.copyOf(log);
        release.countDown();
        holder.join();

        assertThat(whileHeld).as("not torn down under the holder").noneMatch(line -> line.contains("closes"));
        assertThat(log).contains("b closes []", "a closes [the holder's]");
        assertThat(unit.isClosed()).isTrue();
    }

    @Test
    void aModuleThatFailsToCloseDoesNotStopTheOthers() {
        var first = new IllegalStateException("b failed to close");
        var second = new IllegalStateException("a failed to close");
        b.closeFailure = first;
        a.closeFailure = second;
        var before = List.of(a.frame(), b.frame());
        var unit = unit(null);
        var displaced = unit.install();

        assertThatThrownBy(unit::close).isSameAs(first);
        unit.restore(displaced);

        assertThat(first.getSuppressed()).containsExactly(second);
        assertThat(log).contains("b closes []", "a closes []");
        assertThat(List.of(a.frame(), b.frame())).as("the thread is as it was").isEqualTo(before);
    }

    @Test
    void aLifecyclePublishThatThrowsDoesNotStopTheUnit() {
        var unit = new CarriedUnit(List.of(a), null, event -> {
            throw new IllegalStateException("the bus failed");
        });

        var displaced = unit.install();
        unit.close();
        unit.restore(displaced);

        assertThat(log).containsExactly("a closes []");
    }

    /**
     * The application's lifecycle publish lets an {@code Error} of a handler through. The thread
     * must not keep a frame that is marked open (SEC-14): every later unit on it would fail.
     */
    @Test
    void anErrorFromTheStartedPublishLeavesTheThreadAsItWas() {
        var error = new AssertionError("a handler of EventStartedEvent failed");
        var before = List.of(a.frame(), b.frame());
        var unit = new CarriedUnit(List.of(a, b), null, event -> {
            if (event instanceof EventStartedEvent) {
                throw error;
            }
        });

        assertThatThrownBy(unit::install).isSameAs(error);

        assertThat(List.of(a.frame(), b.frame())).as("the thread's own frames").isEqualTo(before);
        assertThat(currentOrigin()).isNull();
        unit.close();
        assertThat(log).as("the unit that did start is still torn down").containsExactly("b closes []", "a closes []");
        assertThat(List.of(a.frame(), b.frame()))
                .as("and the thread is as it was")
                .isEqualTo(before);
    }

    @Test
    void anErrorFromTheEndingPublishStillTearsTheFramesDown() {
        var error = new AssertionError("a handler of EventEndingEvent failed");
        var unit = new CarriedUnit(List.of(a, b), null, event -> {
            if (event instanceof EventEndingEvent) {
                throw error;
            }
        });
        var displaced = unit.install();
        a.put("x");

        assertThatThrownBy(unit::close).isSameAs(error);
        unit.restore(displaced);

        assertThat(log).containsExactly("b closes []", "a closes [x]");
    }

    @Test
    void anErrorFromOneModulesTeardownDoesNotStopTheOthers() {
        var error = new AssertionError("b failed to close");
        var failing = new FrameModule("failing", log) {
            @Override
            public void closeFrame() {
                super.closeFrame();
                throw error;
            }
        };
        var unit = new CarriedUnit(List.of(a, failing), null, event -> {});
        var displaced = unit.install();

        assertThatThrownBy(unit::close).isSameAs(error);
        unit.restore(displaced);

        assertThat(log).containsExactly("failing closes []", "a closes []");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
