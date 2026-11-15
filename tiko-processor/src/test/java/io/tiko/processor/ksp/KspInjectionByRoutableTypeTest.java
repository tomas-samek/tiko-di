package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.KspProgram;
import org.junit.jupiter.api.Test;

/**
 * #724 on the KSP frontend: a Kotlin injection point resolves by every interface a component is
 * routable as, a parameterized one included, and an unqualified one does not take a named provider.
 */
class KspInjectionByRoutableTypeTest {

    @Test
    void secondAndParameterizedInterfacesAreInjectedAndTheProgramBoots() throws Exception {
        var source = KspCompiler.kotlin(
                "Routable.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "",
                "interface A",
                "interface B",
                "interface Repo<T> { fun one(): T }",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Impl : A, B",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class UserRepo : Repo<String> { override fun one() = \"user\" }",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Host(val b: B, val repo: Repo<String>)");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        try (var program = KspProgram.boot(result)) {
            var host = program.get("app.Host");
            var b = host.getClass().getMethod("getB").invoke(host);
            var repo = host.getClass().getMethod("getRepo").invoke(host);
            assertThat(b.getClass().getName()).isEqualTo("app.Impl");
            assertThat(repo.getClass().getName()).isEqualTo("app.UserRepo");
        }
    }

    @Test
    void unqualifiedInjectionWithOnlyNamedProvidersNamesThem() {
        var source = KspCompiler.kotlin(
                "OnlyNamed.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "",
                "interface Greeter",
                "",
                "@Component(scope = Scope.SINGLETON, name = \"formal\")",
                "class Formal : Greeter",
                "",
                "@Component(scope = Scope.SINGLETON, name = \"casual\")",
                "class Casual : Greeter",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Host(val greeter: Greeter)");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages())
                .contains("OnlyNamed.kt:")
                .contains("Cannot resolve dependency: app.Greeter")
                .contains("\"casual\" (app.Casual)", "\"formal\" (app.Formal)")
                .contains("Suggested fixes:")
                .doesNotContain("INTERNAL_ERROR");
    }
}
