package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SingletonGetterPureFactoryTest {

    @Test
    void generatedSingletonGetterContainsNoOverrideCheck() throws Exception {
        var src = JavaFileObjects.forSourceLines(
                "demo.Service",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Service {}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).succeeded();

        var container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        var content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Extract the getService() method body and assert it has no override consultation.
        // The override now lives at the dispatcher (get(Class)) and at factory call sites,
        // not at the per-component getter.
        int idx = content.indexOf("getService()");
        org.assertj.core.api.Assertions.assertThat(idx)
                .as("getService() method present in generated container")
                .isGreaterThan(0);

        int bodyStart = content.indexOf('{', idx);
        int bodyEnd = content.indexOf("\n    }", bodyStart);
        String body = content.substring(bodyStart, bodyEnd);

        org.assertj.core.api.Assertions.assertThat(body)
                .as("getService() body must not consult overrides")
                .doesNotContain("options.hasOverride")
                .doesNotContain("options.getOverride");
    }
}
