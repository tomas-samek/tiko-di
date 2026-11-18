package io.tiko.examples.multimodule.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import io.tiko.examples.multimodule.modulea.NoteDesk;
import io.tiko.runtime.Tiko;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * #685: a unit of work opened through the {@code UnitOfWork} a component of one module was
 * handed is the application's unit. Module A's {@code NoteDesk} opens it; Module B's EVENT-scoped
 * note lives in it.
 */
class MultiModuleUnitOfWorkTest {

    @Test
    void aUnitOpenedInOneModuleHoldsTheEventScopedComponentsOfEveryModule() {
        try (Container container = Tiko.create()) {
            var desk = container.get(NoteDesk.class);

            List<List<String>> readTwice = desk.inOneUnit(() -> List.of(desk.notes(), desk.notes()));
            List<String> another = desk.inOneUnit(desk::notes);

            assertThat(readTwice.get(0))
                    .extracting(note -> note.substring(0, note.indexOf(' ')))
                    .containsExactlyInAnyOrder("module-a", "module-b");
            assertThat(readTwice.get(1)).as("one unit, the same instances").containsExactlyElementsOf(readTwice.get(0));
            assertThat(another).as("another call, other instances").doesNotContainAnyElementsOf(readTwice.get(0));
        }
    }

    @Test
    void aUnitOpenedInOneModulePublishesOneLifecyclePair() {
        try (Container container = Tiko.create()) {
            var lifecycle = new CopyOnWriteArrayList<String>();
            container
                    .getEventBus()
                    .subscribe(EventStartedEvent.class, started -> lifecycle.add("started " + started.eventId()));
            container
                    .getEventBus()
                    .subscribe(EventEndingEvent.class, ending -> lifecycle.add("ending " + ending.eventId()));

            String unit = container
                    .get(NoteDesk.class)
                    .inOneUnit(() -> lifecycle.get(0).substring("started ".length()));

            assertThat(lifecycle).containsExactly("started " + unit, "ending " + unit);
        }
    }
}
