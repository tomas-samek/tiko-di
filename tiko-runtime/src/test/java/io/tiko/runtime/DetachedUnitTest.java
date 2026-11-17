package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #811: the unit of work an async handler runs in is opened in every module of the application,
 * and its lifecycle pair is published once.
 */
class DetachedUnitTest {

    /** A module's container that writes down the frames it is asked for. */
    private static final class FrameModule extends BootstrapTestContainer implements DetachedUnitFrame {
        private final String name;
        private final List<String> log;

        FrameModule(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void runInDetachedFrame(Runnable task, boolean publishLifecycle) {
            log.add(name + " opens, publishLifecycle=" + publishLifecycle);
            task.run();
            log.add(name + " closes");
        }
    }

    @Test
    void detachedUnitNestsASilentFrameOfEveryModuleAroundTheTask() {
        var log = new ArrayList<String>();
        List<Container> modules =
                List.of(new FrameModule("a", log), new BootstrapTestContainer(), new FrameModule("b", log));

        AggregatingContainer.runInDetachedFrames(modules, () -> log.add("task"));

        assertThat(log)
                .as("the container without a detached frame, generated before 0.8.0, is passed by")
                .containsExactly(
                        "a opens, publishLifecycle=false",
                        "b opens, publishLifecycle=false",
                        "task",
                        "b closes",
                        "a closes");
    }

    @Test
    void singleModuleContainerOpensThePublishingFrameItself() {
        var log = new ArrayList<String>();
        var module = new FrameModule("only", log);

        TikoOptions.builder().build().runInDetachedUnit(module, () -> log.add("task"));

        assertThat(log).containsExactly("only opens, publishLifecycle=true", "task", "only closes");
    }

    @Test
    void applicationPublishesOneLifecyclePairAroundTheDetachedUnit() {
        var log = new ArrayList<String>();
        var bus = new LocalEventBus();
        bus.subscribe(EventStartedEvent.class, started -> log.add("started"));
        bus.subscribe(EventEndingEvent.class, ending -> log.add("ending"));
        try (var application = new AggregatingContainer(bus, error -> {}, null, Duration.ofSeconds(1))) {
            // What a module's generated container calls: the options it holds name the application.
            application.options().runInDetachedUnit(new FrameModule("asked directly", log), () -> log.add("task"));
        }

        assertThat(log)
                .as("the application opens the frames of its own modules and publishes the pair")
                .containsExactly("started", "task", "ending");
    }
}
