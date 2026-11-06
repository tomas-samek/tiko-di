package io.tiko.processor.core.javac;

import com.palantir.javapoet.JavaFile;
import io.tiko.processor.core.CodeOutput;
import io.tiko.processor.core.model.ClassDecl;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.tools.StandardLocation;

/** {@link CodeOutput} over javac's {@link Filer}. */
final class JavacCodeOutput implements CodeOutput {

    private final Filer filer;

    JavacCodeOutput(Filer filer) {
        this.filer = filer;
    }

    /**
     * What {@link JavaFile#writeTo(Filer)} does, with {@code originating} added to the type's own
     * originating elements.
     */
    @Override
    public void writeJava(JavaFile file, List<ClassDecl> originating, boolean aggregating) throws IOException {
        // aggregating has no javac meaning: javac's Filer only tracks originating elements.
        var elements = new ArrayList<Element>(file.typeSpec().originatingElements());
        originating.forEach(decl -> elements.add(JavacModel.element(decl)));
        var name = file.packageName().isEmpty()
                ? file.typeSpec().name()
                : file.packageName() + "." + file.typeSpec().name();
        var source = filer.createSourceFile(name, elements.toArray(Element[]::new));
        try (var writer = source.openWriter()) {
            file.writeTo(writer);
        } catch (IOException | RuntimeException e) {
            source.delete();
            throw e;
        }
    }

    @Override
    public void writeResource(String path, String content, List<ClassDecl> originating) throws IOException {
        var elements = originating.stream().map(JavacModel::element).toArray(Element[]::new);
        var resource = filer.createResource(StandardLocation.CLASS_OUTPUT, "", path, elements);
        // Not openWriter(): that encodes with the compiler's -encoding, and resources are UTF-8.
        try (var writer = new OutputStreamWriter(resource.openOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }
}
