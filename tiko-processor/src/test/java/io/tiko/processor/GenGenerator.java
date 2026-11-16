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
 * Generates an interface (by default {@code p.Gen}) in the first round, as a second processor
 * would: a class literal naming it does not resolve yet when Tiko collects the round.
 */
public final class GenGenerator extends AbstractProcessor {

    private final String generatedFqn;
    private boolean done;

    /** Generates the interface {@code p.Gen}. */
    public GenGenerator() {
        this("p.Gen");
    }

    /** @param generatedFqn the fully qualified name of the interface to generate, in a named package */
    public GenGenerator(String generatedFqn) {
        this.generatedFqn = generatedFqn;
    }

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
        int dot = generatedFqn.lastIndexOf('.');
        String source = "package " + generatedFqn.substring(0, dot) + "; public interface "
                + generatedFqn.substring(dot + 1) + " {}";
        try (Writer writer =
                processingEnv.getFiler().createSourceFile(generatedFqn).openWriter()) {
            writer.write(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return false;
    }
}
