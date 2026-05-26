package io.tiko.processor.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Pins the processor's diagnostic format (#166): full cycle paths with the {@code Provider<T>}
 * hint, no generic {@code Validation failed!} trailer, ambiguity reported once per type, and a
 * {@code Suggested fixes:} section on every error.
 */
class ErrorMessageFormatTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
    }

    private static JavaFileObject a() {
        return JavaFileObjects.forSourceLines(
                "demo.A",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class A {",
                "  @Inject public A(B b) {}",
                "}");
    }

    private static JavaFileObject b() {
        return JavaFileObjects.forSourceLines(
                "demo.B",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class B {",
                "  @Inject public B(A a) {}",
                "}");
    }

    @Test
    void cycleErrorShowsFullPathAndProviderHint() {
        Compilation c = compile(a(), b());

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("demo.A -> demo.B -> demo.A");
        CompilationSubject.assertThat(c).hadErrorContaining("Provider<T>");
        CompilationSubject.assertThat(c).hadErrorContaining("Suggested fixes:");
    }

    @Test
    void validationFailureOmitsGenericTrailer() {
        Compilation c = compile(a(), b());

        assertThat(c.errors().stream().noneMatch(d -> d.getMessage(null).contains("Tiko DI: Validation failed!")))
                .as("generic 'Validation failed!' trailer must not be emitted")
                .isTrue();
    }

    @Test
    void ambiguityReportedOncePerType() {
        JavaFileObject iface = JavaFileObjects.forSourceLines("demo.S", "package demo;", "public interface S {}");
        JavaFileObject p1 = JavaFileObjects.forSourceLines(
                "demo.P1",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class P1 implements S {}");
        JavaFileObject p2 = JavaFileObjects.forSourceLines(
                "demo.P2",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class P2 implements S {}");
        JavaFileObject consumer = JavaFileObjects.forSourceLines(
                "demo.C",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class C {",
                "  @Inject public C(S s) {}",
                "}");

        Compilation c = compile(iface, p1, p2, consumer);

        long count = c.errors().stream()
                .filter(d -> d.getMessage(null).contains("Multiple unnamed providers"))
                .count();
        assertThat(count)
                .as("one ambiguity must be reported once, not once per provider")
                .isEqualTo(1);
    }

    @Test
    void annotationMisuseErrorCarriesSuggestedFix() {
        JavaFileObject d = JavaFileObjects.forSourceLines(
                "demo.D",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class D {}");
        JavaFileObject x = JavaFileObjects.forSourceLines(
                "demo.X",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class X {",
                "  @Inject public X() {}",
                "  @Inject public X(D d) {}",
                "}");

        Compilation c = compile(d, x);

        assertThat(c.errors().stream().anyMatch(diag -> {
                    String m = diag.getMessage(null);
                    return m.contains("Multiple constructors") && m.contains("Suggested fixes:");
                }))
                .as("every error, including annotation-misuse, carries a Suggested fixes section")
                .isTrue();
    }
}
