package io.tiko.processor.validation;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Pins enforcement of the "constructor injection only" contract for {@code @Inject} (#160).
 *
 * <p>The site is enforced at the language level: {@code @Inject} is
 * {@code @Target({CONSTRUCTOR, PARAMETER})}, so placing it on a field or method is rejected by
 * javac itself — stronger than any processor check, since the misuse cannot even be written. The
 * one site-misuse {@code @Target} cannot catch — two {@code @Inject} constructors on one component —
 * is where the processor steps in with a guided error. {@code @Inject} on a non-{@code @Component}
 * class is inert (only {@code @Component} constructors are read), so it compiles.
 */
class InjectSiteValidationTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
    }

    @Test
    void injectOnFieldFailsCompilation() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "demo.FieldInject",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class FieldInject {",
                "  @Inject String dep;",
                "}");

        Compilation c = compile(src);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("not applicable");
    }

    @Test
    void injectOnMethodFailsCompilation() {
        // Covers setter and any non-constructor method — all are @Target violations.
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "demo.MethodInject",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class MethodInject {",
                "  @Inject public void setDep(String dep) {}",
                "}");

        Compilation c = compile(src);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("not applicable");
    }

    @Test
    void multipleInjectConstructorsFailWithGuidance() {
        JavaFileObject dep = JavaFileObjects.forSourceLines(
                "demo.Dep",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Dep {}");
        JavaFileObject multi = JavaFileObjects.forSourceLines(
                "demo.MultiCtor",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class MultiCtor {",
                "  @Inject public MultiCtor() {}",
                "  @Inject public MultiCtor(Dep d) {}",
                "}");

        Compilation c = compile(dep, multi);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("Multiple constructors annotated with @Inject");
        CompilationSubject.assertThat(c).hadErrorContaining("Suggested fixes:");
    }

    @Test
    void injectConstructorOnNonComponentCompiles() {
        // @Inject is inert without @Component: only @Component constructors are read.
        JavaFileObject plain = JavaFileObjects.forSourceLines(
                "demo.Plain",
                "package demo;",
                "import io.tiko.annotations.Inject;",
                "public class Plain {",
                "  @Inject public Plain() {}",
                "}");
        JavaFileObject real = JavaFileObjects.forSourceLines(
                "demo.Real",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Real {}");

        Compilation c = compile(plain, real);

        CompilationSubject.assertThat(c).succeeded();
    }

    @Test
    void singleInjectConstructorOnComponentCompiles() {
        JavaFileObject dep = JavaFileObjects.forSourceLines(
                "demo.Dep",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Dep {}");
        JavaFileObject service = JavaFileObjects.forSourceLines(
                "demo.Service",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Service {",
                "  @Inject public Service(Dep d) {}",
                "}");

        Compilation c = compile(dep, service);

        CompilationSubject.assertThat(c).succeeded();
    }
}
