package io.tiko.processor.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class ConfigBinderGeneratorTest {

    @Test
    void simple_record_generates_binder_with_expected_calls() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "io.example.DbConfig",
                "package io.example;",
                "import java.time.Duration;",
                "import java.util.Optional;",
                "import io.tiko.annotations.Configuration;",
                "import io.tiko.annotations.Default;",
                "@Configuration(prefix = \"db\")",
                "public record DbConfig(String url, @Default(\"10\") int maxConnections, Optional<Duration> connectTimeout) {}");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        // Find the generated binder file
        JavaFileObject binder = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("DbConfigBinder"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("DbConfigBinder not generated"));

        String content = new String(binder.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("ctx.requireSection(root, \"db\")");
        assertThat(content).contains("ctx.requireScalar(node, \"url\"");
        assertThat(content).contains("ctx.scalarOrDefault(node, \"maxConnections\"");
        assertThat(content).contains("ctx.optionalScalar(node, \"connectTimeout\"");
        assertThat(content).contains("ctx.checkUnknownKeys(node, \"db\"");
        assertThat(content).contains("return new DbConfig(");
    }

    // ---- #17: nested-record support ----

    @Test
    void direct_nested_record_generates_NestedCoercer_and_references_it() throws IOException {
        JavaFileObject outer = JavaFileObjects.forSourceLines(
                "io.example.AppConfig",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"app\")",
                "public record AppConfig(DbConfig db) {}");
        JavaFileObject inner = JavaFileObjects.forSourceLines(
                "io.example.DbConfig", "package io.example;", "public record DbConfig(String url, int max) {}");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(outer, inner);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        // The nested coercer class is generated.
        JavaFileObject nested = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("DbConfigNestedCoercer_"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("DbConfigNestedCoercer_<hash> not generated"));

        String nestedContent = new String(nested.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(nestedContent).contains("public static");
        assertThat(nestedContent).contains("TypeCoercer<DbConfig> coercer()");
        assertThat(nestedContent).contains("NestedRecordSupport.requireMap");
        assertThat(nestedContent).contains("NestedRecordSupport.requireField(node, \"url\"");
        assertThat(nestedContent).contains("NestedRecordSupport.requireField(node, \"max\"");
        assertThat(nestedContent).contains("NestedRecordSupport.checkUnknownKeys(node, \"DbConfig\")");
        assertThat(nestedContent).contains("return new DbConfig(");

        // The outer binder references the generated nested coercer.
        JavaFileObject outerBinder = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("AppConfigBinder"))
                .findFirst()
                .orElseThrow();
        String outerContent = new String(outerBinder.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(outerContent).contains("DbConfigNestedCoercer_");
        assertThat(outerContent).contains(".coercer()");
    }

    @Test
    void list_of_nested_records_composes_with_CompositeCoercers_list() throws IOException {
        JavaFileObject outer = JavaFileObjects.forSourceLines(
                "io.example.AppConfig",
                "package io.example;",
                "import java.util.List;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"app\")",
                "public record AppConfig(List<Endpoint> endpoints) {}");
        JavaFileObject inner = JavaFileObjects.forSourceLines(
                "io.example.Endpoint", "package io.example;", "public record Endpoint(String host, int port) {}");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(outer, inner);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject outerBinder = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("AppConfigBinder"))
                .findFirst()
                .orElseThrow();
        String outerContent = new String(outerBinder.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(outerContent).contains("CompositeCoercers.list(");
        assertThat(outerContent).contains("EndpointNestedCoercer_");
    }

    @Test
    void map_of_nested_records_composes_with_CompositeCoercers_map() throws IOException {
        JavaFileObject outer = JavaFileObjects.forSourceLines(
                "io.example.AppConfig",
                "package io.example;",
                "import java.util.Map;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"app\")",
                "public record AppConfig(Map<String, Flag> flags) {}");
        JavaFileObject inner = JavaFileObjects.forSourceLines(
                "io.example.Flag", "package io.example;", "public record Flag(boolean enabled) {}");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(outer, inner);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject outerBinder = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("AppConfigBinder"))
                .findFirst()
                .orElseThrow();
        String outerContent = new String(outerBinder.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(outerContent).contains("CompositeCoercers.map(");
        assertThat(outerContent).contains("FlagNestedCoercer_");
    }

    @Test
    void optional_of_nested_record_uses_optionalScalar() throws IOException {
        JavaFileObject outer = JavaFileObjects.forSourceLines(
                "io.example.AppConfig",
                "package io.example;",
                "import java.util.Optional;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"app\")",
                "public record AppConfig(Optional<DbConfig> db) {}");
        JavaFileObject inner = JavaFileObjects.forSourceLines(
                "io.example.DbConfig", "package io.example;", "public record DbConfig(String url) {}");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(outer, inner);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject outerBinder = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("AppConfigBinder"))
                .findFirst()
                .orElseThrow();
        String outerContent = new String(outerBinder.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(outerContent).contains("ctx.optionalScalar(node, \"db\"");
        assertThat(outerContent).contains("DbConfigNestedCoercer_");
    }

    @Test
    void deeply_nested_records_emit_cascaded_coercers() {
        JavaFileObject outer = JavaFileObjects.forSourceLines(
                "io.example.AppConfig",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"app\")",
                "public record AppConfig(Outer outer) {}");
        JavaFileObject mid = JavaFileObjects.forSourceLines(
                "io.example.Outer", "package io.example;", "public record Outer(Inner inner) {}");
        JavaFileObject inner = JavaFileObjects.forSourceLines(
                "io.example.Inner", "package io.example;", "public record Inner(int v) {}");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(outer, mid, inner);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        boolean hasOuterCoercer =
                c.generatedSourceFiles().stream().anyMatch(f -> f.getName().contains("OuterNestedCoercer_"));
        boolean hasInnerCoercer =
                c.generatedSourceFiles().stream().anyMatch(f -> f.getName().contains("InnerNestedCoercer_"));
        assertThat(hasOuterCoercer).as("OuterNestedCoercer_<hash> generated").isTrue();
        assertThat(hasInnerCoercer).as("InnerNestedCoercer_<hash> generated").isTrue();
    }

    @Test
    void same_nested_record_used_in_two_configs_emits_one_coercer() {
        JavaFileObject cfgA = JavaFileObjects.forSourceLines(
                "io.example.A",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"a\")",
                "public record A(Shared s) {}");
        JavaFileObject cfgB = JavaFileObjects.forSourceLines(
                "io.example.B",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"b\")",
                "public record B(Shared s) {}");
        JavaFileObject shared = JavaFileObjects.forSourceLines(
                "io.example.Shared", "package io.example;", "public record Shared(int v) {}");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(cfgA, cfgB, shared);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        long count = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("SharedNestedCoercer_"))
                .count();
        assertThat(count)
                .as("Same nested record used by two configs must generate exactly one coercer")
                .isEqualTo(1);
    }
}
