package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.processing.CodeGenerator;
import com.google.devtools.ksp.processing.Dependencies;
import com.google.devtools.ksp.symbol.KSFile;
import com.palantir.javapoet.JavaFile;
import io.tiko.processor.core.CodeOutput;
import io.tiko.processor.core.model.ClassDecl;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link CodeOutput} over KSP's {@link CodeGenerator}. Java sources go through {@code
 * createNewFile(deps, package, name, "java")}, resources through {@code createNewFileByPath} with
 * the path as given. The originating declarations' files become the {@link Dependencies}:
 * aggregating when the output is derived from many inputs, isolating otherwise; resources are
 * always aggregating.
 */
final class KspCodeOutput implements CodeOutput {

    private final CodeGenerator generator;

    KspCodeOutput(CodeGenerator generator) {
        this.generator = generator;
    }

    @Override
    public void writeJava(JavaFile file, List<ClassDecl> originating, boolean aggregating) throws IOException {
        var dependencies = dependencies(originating, aggregating);
        try (var out = generator.createNewFile(
                        dependencies, file.packageName(), file.typeSpec().name(), "java");
                var writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            file.writeTo(writer);
        }
    }

    @Override
    public void writeResource(String path, String content, List<ClassDecl> originating) throws IOException {
        try (var out = generator.createNewFileByPath(dependencies(originating, true), path, "");
                var writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }

    private static Dependencies dependencies(List<ClassDecl> originating, boolean aggregating) {
        var files = originating.stream()
                .map(KspModel::declaration)
                .flatMap(Optional::stream)
                .map(declaration -> declaration.getContainingFile())
                .filter(Objects::nonNull)
                .distinct()
                .toArray(KSFile[]::new);
        return new Dependencies(aggregating, files);
    }
}
