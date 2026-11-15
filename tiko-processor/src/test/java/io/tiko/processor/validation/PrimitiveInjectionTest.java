package io.tiko.processor.validation;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

/**
 * #748: nothing can provide a primitive, so an injection point of a primitive type says so, with a
 * fix, in place of "Cannot resolve dependency: int". The Kotlin wording is covered by
 * {@code KotlinConstructRulesTest}.
 */
class PrimitiveInjectionTest {

    @Test
    void primitiveConstructorParameterIsReportedAsSuchWithAFix() {
        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(JavaFileObjects.forSourceLines(
                        "demo.Server",
                        "package demo;",
                        "import io.tiko.Scope;",
                        "import io.tiko.annotations.Component;",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class Server {",
                        "  public Server(int port) {}",
                        "}"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("Cannot inject the primitive 'int' (parameter 'port')");
        CompilationSubject.assertThat(c).hadErrorContaining("Inject a small type that holds the value");
    }

    @Test
    void primitiveProducesParameterIsReportedAsSuchWithAFix() {
        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(JavaFileObjects.forSourceLines(
                        "demo.Things",
                        "package demo;",
                        "import io.tiko.annotations.Component;",
                        "import io.tiko.annotations.Produces;",
                        "@Component",
                        "public class Things {",
                        "  @Produces public String label(boolean loud) { return loud ? \"A\" : \"a\"; }",
                        "}"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("Cannot inject the primitive 'boolean' (parameter 'loud')");
    }
}
