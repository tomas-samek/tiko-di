package io.tiko.processor.core;

import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.Visibility;
import java.util.Optional;

/** What code generated into the {@code io.tiko.generated} package can name and call. */
public final class GeneratedCodeAccess {

    private GeneratedCodeAccess() {}

    /**
     * Whether generated code can call a member with this visibility: public, or Kotlin
     * {@code internal}, which is public on the JVM and visible to the module the generated code is
     * compiled into. Java members are never internal.
     */
    public static boolean isCallable(Visibility visibility) {
        return visibility == Visibility.PUBLIC || visibility == Visibility.KOTLIN_INTERNAL;
    }

    /** The class itself or the first class it is nested in that another package cannot name. */
    public static Optional<ClassDecl> firstHidden(ClassDecl decl) {
        for (var current = Optional.of(decl);
                current.isPresent();
                current = current.get().enclosingClass()) {
            if (!isCallable(current.get().visibility())) {
                return current;
            }
        }
        return Optional.empty();
    }
}
