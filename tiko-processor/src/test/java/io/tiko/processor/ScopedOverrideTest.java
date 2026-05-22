package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ScopedOverrideTest {

    @Test
    void requestScopedGetterChecksOverride() throws Exception {
        verifyOverrideInGetter("REQUEST");
    }

    @Test
    void eventScopedGetterChecksOverride() throws Exception {
        verifyOverrideInGetter("EVENT");
    }

    private static void verifyOverrideInGetter(String scopeName) throws Exception {
        var src = JavaFileObjects.forSourceLines(
                "demo.RC",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope." + scopeName + ")",
                "public class RC {}");

        var compilation =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(compilation).succeeded();
        var content = readGeneratedContainer(compilation);

        // Same JavaPoet short-form deviation as T6: type is imported, so `RC.class` not `demo.RC.class`.
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("options.hasOverride(RC.class)")
                .contains("options.getOverride(RC.class).get()");
    }

    private static String readGeneratedContainer(Compilation c) throws Exception {
        var f = c.generatedSourceFiles().stream()
                .filter(x -> x.getName().contains("TikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        return new String(f.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
