package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SingletonOverrideTest {

    @Test
    void singletonGetterChecksOptionsOverrideInsideComputeIfAbsent() throws Exception {
        var src = JavaFileObjects.forSourceLines(
                "demo.OrderService",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class OrderService {}");

        var compilation =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(compilation).succeeded();
        var content = readGeneratedContainer(compilation);

        // JavaPoet imports the user component type, so `OrderService.class` appears
        // unqualified in the emitted source. The structural assertions below check
        // that the override consultation lives inside the computeIfAbsent lambda
        // and routes through TikoOptions.hasOverride / getOverride().get().
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("options.hasOverride(OrderService.class)")
                .contains("options.getOverride(OrderService.class).get()")
                .containsPattern("singletons\\.computeIfAbsent\\([^)]+,[^)]+options\\.hasOverride");
    }

    private static String readGeneratedContainer(Compilation c) throws Exception {
        var f = c.generatedSourceFiles().stream()
                .filter(x -> x.getName().contains("TikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        return new String(f.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
