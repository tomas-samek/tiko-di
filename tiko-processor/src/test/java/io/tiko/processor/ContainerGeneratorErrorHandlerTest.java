package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class ContainerGeneratorErrorHandlerTest {

    @Test
    void generated_container_has_error_handler_field_and_accessor() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "io.example.MyService",
                "package io.example;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.Scope;",
                "@Component(scope = Scope.SINGLETON)",
                "public class MyService { public MyService() {} }");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl not generated"));

        String content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("import io.tiko.ErrorHandler");
        assertThat(content).contains("private final ErrorHandler errorHandler");
        assertThat(content).contains("ErrorHandler getErrorHandler()");
        assertThat(content).contains("EventBus eventBus, ErrorHandler errorHandler");
        assertThat(content).contains("this.errorHandler = errorHandler");
    }
}
