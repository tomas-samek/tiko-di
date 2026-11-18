package io.tiko.coroutines.fixtures;

import io.tiko.Event;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Writes down, for every {@link Note}, the payload of the event its publisher's work descends from. */
@Component(scope = Scope.SINGLETON)
public class NoteOrigins {

    private final List<String> seen = new CopyOnWriteArrayList<>();

    @EventHandler
    public void onNote(Note note, Event<?> wrapper) {
        Object origin = wrapper.getOrigin().<Object>map(Event::getPayload).orElse("nothing");
        seen.add(note.text() + " <- " + origin);
    }

    public List<String> seen() {
        return List.copyOf(seen);
    }
}
