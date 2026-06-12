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
 * Pins {@code @Produces} signature validation (#165).
 *
 * <p>An unsupported signature (void return, primitive return, non-public, abstract) used
 * to slip past the processor; the generated factory then failed to compile, surfacing a
 * cascade of javac errors against generated source the user never wrote. Each case must
 * instead produce one located Tiko error and no leaked generated-code diagnostics.
 */
class ProducesSignatureValidationTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
    }

    @Test
    void voidProducesFailsWithOneCleanError() {
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.Factory",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Factory {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  public void create() {}",
                "}");

        Compilation c = compile(factory);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c)
                .hadErrorContaining(
                        "@Produces method 'io.example.Factory.create' must return a non-void type; factories produce instances.");
        // No generated-code javac errors leak through (the cascade #165 is about): validation
        // fails before codegen, so no "incompatible types" diagnostics on generated source.
        assertThat(c.errors().stream().noneMatch(d -> d.getMessage(null).contains("incompatible types")))
                .as("validation must fail before codegen — no leaked generated-source javac errors")
                .isTrue();
    }

    @Test
    void primitiveProducesFailsCleanly() {
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.Factory",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Factory {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  public int answer() { return 42; }",
                "}");

        Compilation c = compile(factory);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("must return a reference type");
        CompilationSubject.assertThat(c).hadErrorContaining("int");
    }

    @Test
    void nonPublicProducesFailsCleanly() {
        JavaFileObject thing =
                JavaFileObjects.forSourceLines("io.example.Thing", "package io.example;", "public class Thing {}");
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.Factory",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Factory {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  Thing make() { return new Thing(); }", // package-private
                "}");

        Compilation c = compile(thing, factory);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("must be public");
    }

    @Test
    void abstractProducesFailsCleanly() {
        // An abstract @Produces method forces an abstract declaring class, so since #333
        // the class-level shape check fires first — still one clean, located error rather
        // than a javac cascade on generated sources.
        JavaFileObject thing =
                JavaFileObjects.forSourceLines("io.example.Thing", "package io.example;", "public class Thing {}");
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.Factory",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public abstract class Factory {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  public abstract Thing make();",
                "}");

        Compilation c = compile(thing, factory);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("cannot be a @Component: it is not a concrete class");
        CompilationSubject.assertThat(c).hadErrorContaining("Suggested fixes");
    }

    @Test
    void parameterizedProducesFailsCleanly() {
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.Factory",
                "package io.example;",
                "import java.util.List;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Factory {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  public List<String> labels() { return List.of(); }",
                "}");

        Compilation c = compile(factory);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("must return a non-generic type");
        CompilationSubject.assertThat(c).hadErrorContaining("interface MyParamList extends List<MyEntity>");
        // The fix replaces the lenient codegen path that emitted illegal 'List<String>.class':
        // validation now fails before codegen, so no leaked generated-source javac errors (#327).
        assertThat(c.errors().stream().noneMatch(d -> d.getMessage(null).contains("<identifier> expected")))
                .as("validation must fail before codegen — no leaked generated-source javac errors")
                .isTrue();
    }

    @Test
    void rawGenericProducesFailsCleanly() {
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.Factory",
                "package io.example;",
                "import java.util.List;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Factory {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  @SuppressWarnings(\"rawtypes\")",
                "  public List labels() { return List.of(); }",
                "}");

        Compilation c = compile(factory);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("must return a non-generic type");
    }

    @Test
    void namedInterfaceOverParameterizedTypeCompiles() {
        // The prescribed workaround: a named interface fixing the type arguments has its own
        // distinct FQN key, so it routes unambiguously (#327).
        JavaFileObject entity = JavaFileObjects.forSourceLines(
                "io.example.MyEntity", "package io.example;", "public class MyEntity {}");
        JavaFileObject paramList = JavaFileObjects.forSourceLines(
                "io.example.MyParamList",
                "package io.example;",
                "import java.util.List;",
                "public interface MyParamList extends List<MyEntity> {}");
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.Factory",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Factory {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  public MyParamList labels() { return null; }",
                "}");

        Compilation c = compile(entity, paramList, factory);

        CompilationSubject.assertThat(c).succeeded();
    }

    @Test
    void publicObjectReturningProducesCompiles() {
        JavaFileObject thing =
                JavaFileObjects.forSourceLines("io.example.Thing", "package io.example;", "public class Thing {}");
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.Factory",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Factory {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  public Thing make() { return new Thing(); }",
                "}");

        Compilation c = compile(thing, factory);

        CompilationSubject.assertThat(c).succeeded();
    }
}
