package io.tiko.examples.multimodule.modulea;

import io.tiko.Picker;
import io.tiko.Scope;
import io.tiko.UnitOfWork;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.util.List;
import java.util.function.Supplier;

/**
 * Opens a unit of work per call through the built-in {@link UnitOfWork} (#685). The unit is the
 * application's, not Module A's: the notes other modules add are in it too.
 */
@Component(scope = Scope.SINGLETON)
public class NoteDesk {

    private final UnitOfWork unitOfWork;
    private final Picker<UnitNote> notes;

    @Inject
    public NoteDesk(UnitOfWork unitOfWork, Picker<UnitNote> notes) {
        this.unitOfWork = unitOfWork;
        this.notes = notes;
    }

    /** Runs {@code work} in a unit of work of its own. */
    public <T> T inOneUnit(Supplier<T> work) {
        return unitOfWork.supplyInEventScope(work);
    }

    /** The notes of the unit of work the caller is in, each as {@code "<origin> <id>"}. */
    public List<String> notes() {
        return notes.list().stream()
                .map(note -> note.origin() + " " + note.id())
                .toList();
    }
}
