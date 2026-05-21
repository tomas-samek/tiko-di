package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class ContainerGeneratorOptionsConstructorTest {

    @Test
    void generatedContainerAcceptsTikoOptionsAsSixthParam() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "demo.Simple",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Simple {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).succeeded();
        JavaFileObject container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        String content;
        try (var in = container.openInputStream()) {
            content = new String(in.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // The constructor must accept TikoOptions as the sixth (final) param.
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("TikoOptions options")
                .containsPattern(
                        "EventBus[^,]+,\\s*ErrorHandler[^,]+,\\s*ExecutorService[^,]+,\\s*boolean[^,]+,\\s*Duration[^,]+,\\s*TikoOptions");
    }
}
