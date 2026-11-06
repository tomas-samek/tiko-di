package io.tiko.processor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

/**
 * Generates the interface {@code p.Gen} in the first round, as a second processor would: a class
 * literal naming it does not resolve yet when Tiko collects the round.
 */
final class GenGenerator extends AbstractProcessor {

    private boolean done;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (done) {
            return false;
        }
        done = true;
        try (Writer writer = processingEnv.getFiler().createSourceFile("p.Gen").openWriter()) {
            writer.write("package p; public interface Gen {}");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return false;
    }
}
