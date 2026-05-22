package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ScopedGetterPureFactoryTest {

    @Test
    void requestGetterContainsNoOverrideCheck() throws Exception {
        verifyNoOverride("REQUEST", "RC");
    }

    @Test
    void eventGetterContainsNoOverrideCheck() throws Exception {
        verifyNoOverride("EVENT", "EC");
    }

    private static void verifyNoOverride(String scope, String className) throws Exception {
        var src = JavaFileObjects.forSourceLines(
                "demo." + className,
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope." + scope + ")",
                "public class " + className + " {}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).succeeded();

        var container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        var content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        int idx = content.indexOf("get" + className + "()");
        org.assertj.core.api.Assertions.assertThat(idx).isGreaterThan(0);
        int bodyStart = content.indexOf('{', idx);
        int bodyEnd = content.indexOf("\n    }", bodyStart);
        String body = content.substring(bodyStart, bodyEnd);

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("options.hasOverride")
                .doesNotContain("options.getOverride");
    }
}
