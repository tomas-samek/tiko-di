package io.tiko.processor;

import static io.tiko.processor.CompileTesting.compile;

import com.google.testing.compile.CompilationSubject;
import javax.tools.JavaFileObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #831 on javac: a constructor parameter or a {@code @Configuration} record component may carry
 * any name, including the ones generated code uses for its own fields and locals.
 */
class SourceNamesAsLocalsTest {

    private static final String IMPORTS = "import io.tiko.Scope; import io.tiko.annotations.*;";

    private static JavaFileObject source(String name, String... lines) {
        return CompileTesting.source(IMPORTS, name, lines);
    }

    @ParameterizedTest
    @ValueSource(strings = {"container", "instance", "options", "__arg0"})
    void constructorParameterMayCarryANameTheFactoryUses(String name) {
        var compilation = compile(
                source("Dep", "@Component(scope = Scope.SINGLETON)", "public class Dep {}"),
                source(
                        "Host",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class Host {",
                        "  @Inject public Host(Dep " + name + ", Dep other) {}",
                        "}"));

        CompilationSubject.assertThat(compilation).succeeded();
    }

    @ParameterizedTest
    @ValueSource(strings = {"node", "ctx", "root", "f_0"})
    void configurationComponentMayCarryANameTheBinderUses(String name) {
        var compilation = compile(source(
                "AppConfig",
                "@Configuration(prefix = \"app\")",
                "public record AppConfig(String " + name + ", int port) {}"));

        CompilationSubject.assertThat(compilation).succeeded();
    }
}
