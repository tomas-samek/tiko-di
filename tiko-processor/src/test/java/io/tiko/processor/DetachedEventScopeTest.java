package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class DetachedEventScopeTest {

    @Test
    void generatedContainerCarriesDetachedEventScopeMethod() throws IOException {
        JavaFileObject component = JavaFileObjects.forSourceLines(
                "io.example.Simple",
                "package io.example;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.Scope;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Simple {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component);
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("container not generated"));
        String content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(content).contains("void runInDetachedEventScope(Runnable task)");
        // Detachment: save both ThreadLocals, clear, delegate, restore in finally.
        assertThat(content).contains("eventScoped.get()");
        assertThat(content).contains("eventScoped.set(new LinkedHashMap<>())");
        assertThat(content).contains("runInEventScope(task)");
        assertThat(content).contains("__unitFrameOpen.set(__savedFrameOpen)");
        // Package-private: the signature line must not carry public/protected.
        assertThat(content).doesNotContain("public void runInDetachedEventScope");
    }
}
