package io.tiko.processor.config;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;

class ConfigurationValidatorTest {

    @Test
    void nonRecord_emitsError() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
            "io.example.NotARecord",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "@Configuration(prefix = \"db\")",
            "public class NotARecord {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@Configuration must be applied to a record");
    }

    @Test
    void unsupportedType_emitsError() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
            "io.example.WithBigInt",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "@Configuration(prefix = \"x\")",
            "public record WithBigInt(java.math.BigInteger v) {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("unsupported config type 'BigInteger'");
    }

    @Test
    void duplicatePrefix_emitsError() {
        JavaFileObject a = JavaFileObjects.forSourceLines(
            "io.example.A",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "@Configuration(prefix = \"db\")",
            "public record A(String url) {}"
        );
        JavaFileObject b = JavaFileObjects.forSourceLines(
            "io.example.B",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "@Configuration(prefix = \"db\")",
            "public record B(String url) {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(a, b);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("Both records declare prefix 'db'");
    }

    @Test
    void defaultOnOptional_emitsError() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
            "io.example.D",
            "package io.example;",
            "import java.util.Optional;",
            "import io.tiko.annotations.Configuration;",
            "import io.tiko.annotations.Default;",
            "@Configuration(prefix = \"d\")",
            "public record D(@Default(\"x\") Optional<String> v) {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@Default cannot be combined with Optional");
    }

    @Test
    void unparseableDefault_emitsError() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
            "io.example.U",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "import io.tiko.annotations.Default;",
            "@Configuration(prefix = \"u\")",
            "public record U(@Default(\"abc\") int v) {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@Default('abc') on int field 'v'");
    }

    @Test
    void recursiveRecord_emitsError() {
        JavaFileObject a = JavaFileObjects.forSourceLines(
            "io.example.A",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "@Configuration(prefix = \"a\")",
            "public record A(B b) {}"
        );
        JavaFileObject b = JavaFileObjects.forSourceLines(
            "io.example.B",
            "package io.example;",
            "public record B(A a) {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(a, b);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("Recursive record reference");
    }

    @Test
    void nonRecursiveNestedRecords_compileSuccessfully() {
        JavaFileObject outer = JavaFileObjects.forSourceLines(
            "io.example.Outer",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "@Configuration(prefix = \"outer\")",
            "public record Outer(Inner inner) {}"
        );
        JavaFileObject inner = JavaFileObjects.forSourceLines(
            "io.example.Inner",
            "package io.example;",
            "public record Inner(int v) {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(outer, inner);
        assertThat(c).succeeded();
        // Both the top-level binder and a NestedCoercer for Inner must be generated.
        assertThat(c).generatedSourceFile("io.tiko.generated.config.OuterBinder");
        boolean hasInnerCoercer = c.generatedSourceFiles().stream()
            .anyMatch(f -> f.getName().contains("InnerNestedCoercer_"));
        assert hasInnerCoercer : "expected InnerNestedCoercer_<hash> to be generated";
    }

    @Test
    void recordInsideList_compilesAndGeneratesNestedCoercer() {
        JavaFileObject outer = JavaFileObjects.forSourceLines(
            "io.example.Outer",
            "package io.example;",
            "import java.util.List;",
            "import io.tiko.annotations.Configuration;",
            "@Configuration(prefix = \"outer\")",
            "public record Outer(List<Inner> inners) {}"
        );
        JavaFileObject inner = JavaFileObjects.forSourceLines(
            "io.example.Inner",
            "package io.example;",
            "public record Inner(int v) {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(outer, inner);
        assertThat(c).succeeded();
        assertThat(c).generatedSourceFile("io.tiko.generated.config.OuterBinder");
        boolean hasInnerCoercer = c.generatedSourceFiles().stream()
            .anyMatch(f -> f.getName().contains("InnerNestedCoercer_"));
        assert hasInnerCoercer : "expected InnerNestedCoercer_<hash> to be generated";
    }
}
