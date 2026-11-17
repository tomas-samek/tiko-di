package io.tiko.examples.multimodule.modulea;

import io.tiko.Picker;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Inject;

/**
 * Reads the notes of its own unit of work on the event executor. The handler runs in a unit that
 * is open in every module of the application, so the picker lists the EVENT-scoped notes other
 * modules add as well as Module A's own (#811).
 */
@Component(scope = Scope.SINGLETON)
public class AsyncNoteReader {

    private final Picker<UnitNote> notes;

    @Inject
    public AsyncNoteReader(Picker<UnitNote> notes) {
        this.notes = notes;
    }

    @EventHandler(async = true)
    public void onNotesAsked(NotesAsked asked) {
        asked.answer().complete(notes.list().stream().map(UnitNote::origin).toList());
    }
}
