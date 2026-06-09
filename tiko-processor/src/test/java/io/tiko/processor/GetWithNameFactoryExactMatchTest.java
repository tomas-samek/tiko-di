package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for #304: the three generated dispatchers must use a consistent
 * matching rule for {@code @Produces} outputs. {@code get(Class)} and {@code getAll(Class)}
 * already key on exact {@code type == ProducedType.class} (the latter since #303); the named
 * {@code get(Class, String)} arms still used {@code type.isAssignableFrom(ProducedType.class)},
 * letting a too-broad requested type (e.g. {@code get(Object.class, "primary")}) collide
 * first-match-wins across distinct named producers. Align it on exact match.
 *
 * <p>For a name-only producer, {@code isAssignableFrom(ProducedType.class)} is emitted only by
 * the {@code get(Class, String)} arm — {@code get(Class)} skips named producers and getAll is
 * exact — so its absence is an unambiguous signal that all three dispatchers now agree.
 */
class GetWithNameFactoryExactMatchTest {

    private static final JavaFileObject TOKEN =
            JavaFileObjects.forSourceLines("demo.Token", "package demo;", "public class Token {}");

    private static final JavaFileObject FACTORY = JavaFileObjects.forSourceLines(
            "demo.Tokens",
            "package demo;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.Produces;",
            "@Component(scope = Scope.SINGLETON)",
            "public class Tokens {",
            "    @Produces(scope = Scope.SINGLETON, name = \"primary\")",
            "    public Token primary() { return new Token(); }",
            "}");

    private static String generatedContainer() throws Exception {
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(TOKEN, FACTORY);
        assertThat(c).succeeded();
        var container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        return new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void namedFactoryArmDoesNotWidenWithAssignability() throws Exception {
        var content = generatedContainer();

        org.assertj.core.api.Assertions.assertThat(content).doesNotContain("isAssignableFrom(Token.class)");
    }

    @Test
    void namedFactoryArmKeysOnNameAndExactType() throws Exception {
        var content = generatedContainer();

        org.assertj.core.api.Assertions.assertThat(content).contains("\"primary\".equals(name) && type == Token.class");
    }
}
