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

/**
 * A component implementing a parameterized interface must compile and route under the
 * erased interface type (#330). Class literals are erased in Java, so the dispatch arm
 * has to be {@code type == Repo.class} — emitting the type arguments
 * ({@code Repo<User>.class}) is a parse error inside the generated container. Same
 * defect class as #327, which covered the {@code @Produces} return-type path only.
 */
class GenericInterfaceRoutingTest {

    @Test
    void componentImplementingGenericInterfaceCompilesAndRoutesUnderErasedType() throws IOException {
        JavaFileObject user = JavaFileObjects.forSourceLines("demo.User", "package demo;", "public class User {}");
        JavaFileObject repo =
                JavaFileObjects.forSourceLines("demo.Repo", "package demo;", "public interface Repo<T> {}");
        JavaFileObject userRepo = JavaFileObjects.forSourceLines(
                "demo.UserRepo",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UserRepo implements Repo<User> {",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(user, repo, userRepo);

        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject containerSource = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl was not generated"));

        String source = new String(containerSource.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(source)
                .as("dispatch must use the erased interface — type arguments don't survive class literals")
                .contains("type == Repo.class")
                .doesNotContain("Repo<User>.class");
    }
}
