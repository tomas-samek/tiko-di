package io.tiko.processor.core.javac;

import io.tiko.processor.core.Diagnostics;
import io.tiko.processor.core.model.Location;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;

/** {@link Diagnostics} over javac's {@link Messager}, reported on the location's element. */
final class JavacDiagnostics implements Diagnostics {

    private final Messager messager;
    private boolean errors;

    JavacDiagnostics(Messager messager) {
        this.messager = messager;
    }

    @Override
    public void error(Location at, String message) {
        errors = true;
        print(Diagnostic.Kind.ERROR, at, message);
    }

    @Override
    public void warning(Location at, String message) {
        print(Diagnostic.Kind.WARNING, at, message);
    }

    @Override
    public void note(Location at, String message) {
        print(Diagnostic.Kind.NOTE, at, message);
    }

    @Override
    public boolean hasErrors() {
        return errors;
    }

    private void print(Diagnostic.Kind kind, Location at, String message) {
        switch (at) {
            case null -> messager.printMessage(kind, message);
            case JavacLocation javac -> printOn(kind, javac, message);
            default -> throw new IllegalArgumentException("Not a javac location: " + at);
        }
    }

    private void printOn(Diagnostic.Kind kind, JavacLocation at, String message) {
        if (at.annotation() == null) {
            messager.printMessage(kind, message, at.element());
        } else {
            messager.printMessage(kind, message, at.element(), at.annotation());
        }
    }
}
