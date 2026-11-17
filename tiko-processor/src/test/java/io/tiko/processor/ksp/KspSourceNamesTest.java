package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.KspProgram;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #831: generated Java names its own locals, so a name from the source never has to be usable as
 * one. A Kotlin name can be a Java keyword ({@code default}) or, in backticks, hold a space; a Java
 * name can be one the generated code uses itself ({@code container}, {@code node}). A class that
 * Java can't name at all is a located error.
 */
class KspSourceNamesTest {

    private static final String[] KOTLIN_HEAD = {
        "package app",
        "",
        "import io.tiko.Scope",
        "import io.tiko.annotations.Component",
        "import io.tiko.annotations.Configuration",
        "",
    };

    private static com.tschuchort.compiletesting.SourceFile kotlin(String... lines) {
        var all = new String[KOTLIN_HEAD.length + lines.length];
        System.arraycopy(KOTLIN_HEAD, 0, all, 0, KOTLIN_HEAD.length);
        System.arraycopy(lines, 0, all, KOTLIN_HEAD.length, lines.length);
        return KspCompiler.kotlin("Names.kt", all);
    }

    @Test
    void kotlinConstructorParametersNamedLikeAKeywordOrWithASpaceAreInjected() throws Exception {
        var source = kotlin(
                "@Component(scope = Scope.SINGLETON)",
                "class Dep",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Host(val default: Dep, val `spare dep`: Dep)");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        try (var program = KspProgram.boot(result)) {
            var host = program.get("app.Host");
            assertThat(host.getClass().getMethod("getDefault").invoke(host)).isSameAs(program.get("app.Dep"));
        }
    }

    @Test
    void kotlinConfigurationPropertiesNamedLikeAKeywordOrWithASpaceAreBound(@TempDir Path dir) throws Exception {
        var source = kotlin(
                "@Configuration(prefix = \"app\")",
                "@JvmRecord",
                "data class AppConfig(val default: String, val `spare host`: String)",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Server(val config: AppConfig) {",
                "    fun describe(): String = config.default + \"/\" + config.`spare host`",
                "}");
        var yaml = dir.resolve("app.yaml");
        Files.writeString(
                yaml,
                String.join("\n", "app:", "  default: main.example", "  spare host: spare.example"),
                StandardCharsets.UTF_8);

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        try (var program = KspProgram.boot(result, yaml)) {
            var server = program.get("app.Server");
            assertThat(server.getClass().getMethod("describe").invoke(server)).isEqualTo("main.example/spare.example");
        }
    }

    @Test
    void javaNamesTheGeneratedCodeUsesItselfAreInjectedAndBound(@TempDir Path dir) throws Exception {
        var part = KspCompiler.java(
                "app/Part.java",
                "package app;",
                "",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "public class Part {}");
        var settings = KspCompiler.java(
                "app/Settings.java",
                "package app;",
                "",
                "import io.tiko.annotations.Configuration;",
                "",
                "@Configuration(prefix = \"app\")",
                "public record Settings(String node, String ctx, String root) {}");
        var assembly = KspCompiler.java(
                "app/Assembly.java",
                "package app;",
                "",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "public class Assembly {",
                "    private final String described;",
                "",
                "    @Inject",
                "    public Assembly(Part container, Part instance, Settings options) {",
                "        this.described = (container == instance) + \"/\" + options.node() + options.ctx() + options.root();",
                "    }",
                "",
                "    public String describe() { return described; }",
                "}");
        var yaml = dir.resolve("app.yaml");
        Files.writeString(
                yaml, String.join("\n", "app:", "  node: n", "  ctx: c", "  root: r"), StandardCharsets.UTF_8);

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), part, settings, assembly);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        try (var program = KspProgram.boot(result, yaml)) {
            var built = program.get("app.Assembly");
            assertThat(built.getClass().getMethod("describe").invoke(built)).isEqualTo("true/ncr");
        }
    }

    @Test
    void proxiedInterfaceParameterNamedLikeAKeywordCompiles() {
        var source = kotlin(
                "interface Ctx { fun describe(default: String): String }",
                "",
                "@Component(scope = Scope.EVENT)",
                "class CtxImpl : Ctx { override fun describe(default: String): String = default }",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Host(val ctx: Ctx)");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
    }

    static Stream<Arguments> classesJavaCannotName() {
        return Stream.of(
                Arguments.of(
                        "component named with a Java keyword",
                        "new",
                        "@Component(scope = Scope.SINGLETON)",
                        "class `new`"),
                Arguments.of(
                        "component with a space in its name",
                        "My Service",
                        "@Component(scope = Scope.SINGLETON)",
                        "class `My Service`"),
                Arguments.of(
                        "configuration named with a Java keyword",
                        "static",
                        "@Configuration(prefix = \"app\") @JvmRecord",
                        "data class `static`(val host: String)"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("classesJavaCannotName")
    void classThatJavaCannotNameIsALocatedError(String name, String className, String annotation, String declaration) {
        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), kotlin(annotation, declaration));

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages())
                .contains("Names.kt:")
                .contains("'" + className + "' is not a Java identifier")
                .contains("Suggested fixes:")
                .doesNotContain("processing failed")
                .doesNotContain(".java:");
    }
}
