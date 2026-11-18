package io.tiko.examples.multimodule.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import io.tiko.examples.multimodule.modulea.NoteDesk;
import io.tiko.runtime.CarriedUnit;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.UnitCarrier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * #677: a unit of work that is carried from thread to thread is the application's unit. It holds
 * the EVENT-scoped notes of Module A and Module B on every thread it is put on, and its lifecycle
 * pair is published once.
 */
class MultiModuleCarriedUnitTest {

    /** The notes {@code desk} reads with {@code unit} installed on a thread of its own. */
    private static List<String> notesOnAnotherThread(CarriedUnit unit, NoteDesk desk) throws InterruptedException {
        var notes = new AtomicReference<List<String>>();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            var displaced = unit.install();
            try {
                notes.set(desk.notes());
            } finally {
                unit.restore(displaced);
            }
        });
        thread.setUncaughtExceptionHandler((ignored, thrown) -> failure.set(thrown));
        thread.start();
        thread.join();
        assertThat(failure.get()).as("failure on the other thread").isNull();
        return notes.get();
    }

    @Test
    void aCarriedUnitHoldsTheEventComponentsOfEveryModuleOnEveryThread() throws InterruptedException {
        try (Container container = Tiko.create()) {
            var lifecycle = new CopyOnWriteArrayList<String>();
            container
                    .getEventBus()
                    .subscribe(EventStartedEvent.class, started -> lifecycle.add("started " + started.eventId()));
            container
                    .getEventBus()
                    .subscribe(EventEndingEvent.class, ending -> lifecycle.add("ending " + ending.eventId()));
            var desk = container.get(NoteDesk.class);
            var unit = ((UnitCarrier) container).openCarriedUnit(null);

            List<String> first = notesOnAnotherThread(unit, desk);
            List<String> second = notesOnAnotherThread(unit, desk);
            unit.close();

            assertThat(first)
                    .extracting(note -> note.substring(0, note.indexOf(' ')))
                    .containsExactlyInAnyOrder("module-a", "module-b");
            assertThat(second).as("the same instances on the second thread").containsExactlyElementsOf(first);
            assertThat(lifecycle).hasSize(2);
            String id = lifecycle.get(0).substring("started ".length());
            assertThat(lifecycle).containsExactly("started " + id, "ending " + id);
        }
    }
}
