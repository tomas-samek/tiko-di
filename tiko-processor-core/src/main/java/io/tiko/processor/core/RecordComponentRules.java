package io.tiko.processor.core;

import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.Location;
import java.util.List;
import java.util.Optional;

/**
 * The record rule shared by the processors that read record components: a record whose
 * components the model can't tell ({@link ClassDecl#recordComponentsKnown()}) is rejected with a
 * Tiko-format error before anything reads them, so no caller binds a guess. It reads model facts
 * only, and on javac, which reads every record header, it never fires.
 */
public final class RecordComponentRules {

    private RecordComponentRules() {}

    /**
     * A record with unknown components: where it is, what's wrong, and the fixes.
     *
     * @param location the record to report on
     * @param problem what's wrong and why
     * @param fixes the suggested fixes, in order
     */
    public record Violation(Location location, String problem, List<String> fixes) {}

    /**
     * KSP shows a Java record only as its accessors and constructors, so a declared canonical
     * constructor next to another constructor whose parameters all match accessors could be
     * either of them.
     */
    public static Optional<Violation> unknownComponents(ClassDecl recordDecl) {
        if (recordDecl.recordComponentsKnown()) {
            return Optional.empty();
        }
        var name = String.join(".", recordDecl.className().simpleNames());
        return Optional.of(new Violation(
                recordDecl.location(),
                "The components of record '" + name + "' can't be told on KSP: it declares its canonical"
                        + " constructor next to another constructor whose parameters all match its accessors."
                        + " KSP doesn't expose a Java record's header, only its accessors and constructors, so"
                        + " either constructor could be the canonical one. javac builds read the header and are"
                        + " unaffected.",
                List.of(
                        "Let the compiler supply the canonical constructor: drop the declared (or compact) one",
                        "Give the other constructor a parameter that no accessor matches by name and type")));
    }
}
