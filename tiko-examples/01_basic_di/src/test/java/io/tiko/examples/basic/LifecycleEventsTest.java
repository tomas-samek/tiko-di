package io.tiko.examples.basic;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.events.ApplicationEndingEvent;
import io.tiko.events.ApplicationStartedEvent;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import io.tiko.runtime.Tiko;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LifecycleEventsTest {

    @Test
    void applicationStarted_publishedOnceAfterContainerBoot() {
        LifecycleRecorder recorder;
        try (Container container = Tiko.create()) {
            recorder = container.get(LifecycleRecorder.class);
            assertThat(recorder.getEvents()).hasAtLeastOneElementOfType(ApplicationStartedEvent.class);
            assertThat(recorder.getEvents().stream()
                            .filter(ApplicationStartedEvent.class::isInstance)
                            .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void applicationEnding_publishedBeforeShutdown_withUptime() {
        LifecycleRecorder recorder;
        try (Container container = Tiko.create()) {
            recorder = container.get(LifecycleRecorder.class);
        }
        var ending = recorder.getEvents().stream()
                .filter(ApplicationEndingEvent.class::isInstance)
                .map(ApplicationEndingEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(ending.timestamp()).isNotNull();
        assertThat(ending.uptime()).isGreaterThanOrEqualTo(Duration.ZERO);
    }

    @Test
    void eventScope_publishesStartAndEndingWithSameEventIdAndDuration() {
        try (Container container = Tiko.create()) {
            var recorder = container.get(LifecycleRecorder.class);
            var before = recorder.getEvents().size();

            container.runInEventScope(() -> {});

            var after =
                    recorder.getEvents().subList(before, recorder.getEvents().size());
            var started = after.stream()
                    .filter(EventStartedEvent.class::isInstance)
                    .map(EventStartedEvent.class::cast)
                    .findFirst()
                    .orElseThrow();
            var ending = after.stream()
                    .filter(EventEndingEvent.class::isInstance)
                    .map(EventEndingEvent.class::cast)
                    .findFirst()
                    .orElseThrow();

            assertThat(started.eventId()).isNotBlank();
            assertThat(ending.eventId()).isEqualTo(started.eventId());
            assertThat(after.indexOf(started)).isLessThan(after.indexOf(ending));
            assertThat(ending.duration()).isGreaterThanOrEqualTo(Duration.ZERO);
        }
    }

    @Test
    void supplyInEventScope_publishesLifecycleEvents() {
        try (Container container = Tiko.create()) {
            var recorder = container.get(LifecycleRecorder.class);
            var before = recorder.getEvents().size();

            var result = container.supplyInEventScope(() -> 42);
            assertThat(result).isEqualTo(42);

            var after =
                    recorder.getEvents().subList(before, recorder.getEvents().size());
            assertThat(after).hasAtLeastOneElementOfType(EventStartedEvent.class);
            assertThat(after).hasAtLeastOneElementOfType(EventEndingEvent.class);
        }
    }
}
