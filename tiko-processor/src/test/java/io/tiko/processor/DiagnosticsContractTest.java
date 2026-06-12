package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Pins the error-message contract for malformed inputs that used to surface as raw javac
 * errors inside generated sources or as processor stack traces (#333): each must fail
 * with a located Tiko diagnostic (location + explanation + suggested fix) — or compile
 * to correct code where the input is actually legal (special characters in qualifiers).
 */
class DiagnosticsContractTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
    }

    private static void assertNoProcessorCrash(Compilation c) {
        assertThat(c.errors().stream().map(d -> d.getMessage(null)))
                .as("a malformed input must never surface as a processor stack trace")
                .noneMatch(m -> m.contains("Tiko DI processing failed"));
    }

    @Test
    void rawProviderInjectionFailsWithLocatedDiagnostic() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "demo.A",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class A {",
                "  @Inject public A(io.tiko.Provider p) {}",
                "}");

        Compilation c = compile(src);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("Provider");
        CompilationSubject.assertThat(c).hadErrorContaining("Suggested fixes");
        assertNoProcessorCrash(c);
    }

    @Test
    void rawPickerInjectionFailsWithLocatedDiagnostic() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "demo.A",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class A {",
                "  @Inject public A(io.tiko.Picker p) {}",
                "}");

        Compilation c = compile(src);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("Picker");
        CompilationSubject.assertThat(c).hadErrorContaining("Suggested fixes");
        assertNoProcessorCrash(c);
    }

    @Test
    void eventHandlerOnNonComponentClassFailsWithLocatedDiagnostic() {
        JavaFileObject event =
                JavaFileObjects.forSourceLines("demo.PingEvent", "package demo;", "public class PingEvent {}");
        JavaFileObject plain = JavaFileObjects.forSourceLines(
                "demo.Plain",
                "package demo;",
                "import io.tiko.annotations.EventHandler;",
                "public class Plain {",
                "  @EventHandler",
                "  public void onPing(PingEvent event) {}",
                "}");
        // A registered component so the round generates a container at all.
        JavaFileObject anchor = JavaFileObjects.forSourceLines(
                "demo.Anchor",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Anchor {}");

        Compilation c = compile(event, plain, anchor);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("EventHandler");
        CompilationSubject.assertThat(c).hadErrorContaining("Component");
        CompilationSubject.assertThat(c).hadErrorContaining("Suggested fixes");
        assertNoProcessorCrash(c);
    }

    @Test
    void abstractComponentFailsWithLocatedDiagnostic() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "demo.AbstractSvc",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public abstract class AbstractSvc {",
                "  public AbstractSvc() {}",
                "}");

        Compilation c = compile(src);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("AbstractSvc");
        CompilationSubject.assertThat(c).hadErrorContaining("Suggested fixes");
        assertNoProcessorCrash(c);
    }

    @Test
    void innerClassComponentFailsWithLocatedDiagnostic() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "demo.Outer",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "public class Outer {",
                "  @Component(scope = Scope.SINGLETON)",
                "  public class Inner {",
                "    public Inner() {}",
                "  }",
                "}");

        Compilation c = compile(src);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("Inner");
        CompilationSubject.assertThat(c).hadErrorContaining("Suggested fixes");
        assertNoProcessorCrash(c);
    }

    @Test
    void duplicateSimpleNamesAcrossPackagesFailWithLocatedDiagnostic() {
        JavaFileObject a = JavaFileObjects.forSourceLines(
                "demo.a.Dup",
                "package demo.a;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Dup {}");
        JavaFileObject b = JavaFileObjects.forSourceLines(
                "demo.b.Dup",
                "package demo.b;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Dup {}");

        Compilation c = compile(a, b);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("demo.a.Dup");
        CompilationSubject.assertThat(c).hadErrorContaining("demo.b.Dup");
        CompilationSubject.assertThat(c).hadErrorContaining("Suggested fixes");
        assertNoProcessorCrash(c);
        assertThat(c.errors().stream().map(d -> d.getMessage(null)))
                .noneMatch(m -> m.contains("FilerException") || m.contains("Attempt to recreate"));
    }

    @Test
    void quoteInQualifierCompilesToCorrectCode() {
        JavaFileObject iface =
                JavaFileObjects.forSourceLines("demo.Greeter", "package demo;", "public interface Greeter {}");
        JavaFileObject named = JavaFileObjects.forSourceLines(
                "demo.QuotedGreeter",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON, name = \"a\\\"b\")",
                "public class QuotedGreeter implements Greeter {}");
        JavaFileObject consumer = JavaFileObjects.forSourceLines(
                "demo.Consumer",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "import io.tiko.annotations.Named;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Consumer {",
                "  @Inject public Consumer(@Named(\"a\\\"b\") Greeter g) {}",
                "}");

        Compilation c = compile(iface, named, consumer);

        CompilationSubject.assertThat(c).succeeded();
    }
}
