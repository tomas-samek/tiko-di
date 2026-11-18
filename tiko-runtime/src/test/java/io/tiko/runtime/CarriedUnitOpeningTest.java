package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.Event;
import io.tiko.events.EventStartedEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #677: a carried unit of work is opened over one module's container, or over every module of an
 * application, and whoever holds a {@code UnitOfWork} or the booted container reaches the same one.
 */
class CarriedUnitOpeningTest {

    /** A module's container that notes when its frame is swapped. */
    private static final class FrameModule extends BootstrapTestContainer implements DetachedUnitFrame {
        private final String name;
        private final List<String> log;
        private UnitFrame current = new UnitFrame(new LinkedHashMap<>(), false);

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
            log.add(name + (frame.open() ? " gets the unit's frame" : " gets its own frame back"));
            var previous = current;
            current = frame;
            return previous;
        }

        @Override
        public void closeFrame() {
            log.add(name + " closes");
        }
    }

    /** A container that carries units: it notes the origin it is asked with and hands out one unit. */
    private static final class CarryingContainer extends BootstrapTestContainer implements UnitCarrier {
        final List<Event<?>> askedWith = new ArrayList<>();
        final CarriedUnit unit = new CarriedUnit(List.of(), null, event -> {});
        boolean unitOpen;

        @Override
        public CarriedUnit openCarriedUnit(Event<?> origin) {
            askedWith.add(origin);
            return unit;
        }

        @Override
        public boolean hasUnitOpen() {
            return unitOpen;
        }
    }

    private static void installAndTakeOff(CarriedUnit unit) {
        unit.restore(unit.install());
    }

    @Test
    void aSingleModuleOpensTheUnitOverItselfAndPublishesThroughItsOwnGate() {
        var log = new ArrayList<String>();
        var module = new FrameModule("only", log);

        var unit = TikoOptions.builder()
                .build()
                .openCarriedUnit(
                        module,
                        event -> log.add("only publishes " + event.getClass().getSimpleName()),
                        null);
        installAndTakeOff(unit);

        assertThat(log)
                .containsExactly(
                        "only gets the unit's frame",
                        "only publishes " + EventStartedEvent.class.getSimpleName(),
                        "only gets its own frame back");
    }

    @Test
    void anApplicationOpensTheUnitOverEveryModuleAndPublishesOnce() {
        var log = new ArrayList<String>();
        List<Container> modules =
                List.of(new FrameModule("a", log), new BootstrapTestContainer(), new FrameModule("b", log));

        var unit = AggregatingContainer.openCarriedUnit(
                modules,
                null,
                event -> log.add("application publishes " + event.getClass().getSimpleName()));
        installAndTakeOff(unit);

        assertThat(log)
                .as("the container without a frame, generated before 0.8.0, is passed by")
                .containsExactly(
                        "a gets the unit's frame",
                        "b gets the unit's frame",
                        "application publishes " + EventStartedEvent.class.getSimpleName(),
                        "b gets its own frame back",
                        "a gets its own frame back");
    }

    @Test
    void aModuleUnderAnApplicationLeavesTheUnitToTheApplication() {
        var log = new ArrayList<String>();
        var module = new FrameModule("module", log);
        var options = TikoOptions.builder().build();
        try (var application = new AggregatingContainer(
                new LocalEventBus(), ctx -> {}, null, java.time.Duration.ofSeconds(1), options)) {

            var unit = application.options().openCarriedUnit(module, event -> log.add("module publishes"), null);
            installAndTakeOff(unit);

            assertThat(log)
                    .as("opened by the application, over its own modules")
                    .isEmpty();
        }
    }

    @Test
    void theUnitOfWorkAComponentHoldsOpensTheApplicationsUnit() {
        var application = new CarryingContainer();
        var options = TikoOptions.builder().build();
        options.applicationContainer(application);
        Event<String> origin = EventChainContext.wrap("origin");

        var unit = new ContainerUnitOfWork(new BootstrapTestContainer(), options).openCarriedUnit(origin);

        assertThat(unit).isSameAs(application.unit);
        assertThat(application.askedWith).containsExactly(origin);
    }

    @Test
    void aContainerThatCannotCarryAUnitSaysSo() {
        var unitOfWork = new ContainerUnitOfWork(new BootstrapTestContainer(), null);

        assertThatThrownBy(() -> unitOfWork.openCarriedUnit(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BootstrapTestContainer.class.getName())
                .hasMessageContaining("0.8.0");
        assertThatThrownBy(unitOfWork::hasUnitOpen)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BootstrapTestContainer.class.getName());
    }

    /** A unit of work is the application's, so it is open when any module has it open. */
    @Test
    void anApplicationHasAUnitOpenWhenAnyModuleHasOne() {
        var first = new CarryingContainer();
        var second = new CarryingContainer();
        List<Container> modules = List.of(first, new BootstrapTestContainer(), second);

        boolean none = AggregatingContainer.hasUnitOpen(modules);
        second.unitOpen = true;
        boolean one = AggregatingContainer.hasUnitOpen(modules);

        assertThat(none).as("no module has a unit open").isFalse();
        assertThat(one).as("one module has").isTrue();
    }

    @Test
    void theUnitOfWorkAComponentHoldsAnswersForTheApplication() {
        var application = new CarryingContainer();
        var options = TikoOptions.builder().build();
        options.applicationContainer(application);
        var unitOfWork = new ContainerUnitOfWork(new BootstrapTestContainer(), options);

        boolean before = unitOfWork.hasUnitOpen();
        application.unitOpen = true;

        assertThat(before).isFalse();
        assertThat(unitOfWork.hasUnitOpen()).isTrue();
    }

    /** {@code Tiko.create()} hands out a handle over the container; the handle carries units if the container does. */
    @Test
    void theBootedContainerHandleForwardsToItsContainer() {
        try (Container booted = Tiko.create()) {
            assertThat(booted).isInstanceOf(UnitCarrier.class);
            var carrier = (UnitCarrier) booted;

            assertThatThrownBy(() -> carrier.openCarriedUnit(null))
                    .as("the stub container of this module's tests has no frame to offer")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(StubContainer.class.getName());
        }
    }
}
