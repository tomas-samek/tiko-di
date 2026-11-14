package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.KspProgram;
import org.junit.jupiter.api.Test;

/**
 * #706: a Tiko annotation imported under an alias ({@code import ...Component as Comp}) is the same
 * annotation. Kotlin writes the alias at the use site, so an annotation is recognised by the type it
 * resolves to, never by the name it was written as.
 */
class KspImportAliasTest {

    @Test
    void aliasedComponentAndNamedAnnotationsWireLikeTheUnaliasedOnes() throws Exception {
        var source = KspCompiler.kotlin(
                "Aliased.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component as Comp",
                "import io.tiko.annotations.Named as Qualifier",
                "",
                "interface Greeter { fun greet(): String }",
                "",
                "@Comp(scope = Scope.SINGLETON, name = \"formal\")",
                "class Formal : Greeter { override fun greet() = \"Good day\" }",
                "",
                "@Comp(scope = Scope.SINGLETON, name = \"casual\")",
                "class Casual : Greeter { override fun greet() = \"Hi\" }",
                "",
                "@Comp(scope = Scope.SINGLETON)",
                "class Host(@param:Qualifier(\"casual\") val greeter: Greeter)");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        try (var program = KspProgram.boot(result)) {
            var host = program.get("app.Host");
            var greeter = host.getClass().getMethod("getGreeter").invoke(host);
            assertThat(greeter.getClass().getName()).isEqualTo("app.Casual");
        }
    }

    @Test
    void aliasedEventHandlerAndProducesAnnotationsAreCollected() {
        var source = KspCompiler.kotlin(
                "AliasedMembers.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.annotations.EventHandler as On",
                "import io.tiko.annotations.Produces as Makes",
                "",
                "class Clock",
                "class Tick",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Wiring {",
                "    @Makes(scope = Scope.SINGLETON) fun clock(): Clock = Clock()",
                "    @On fun onTick(tick: Tick) {}",
                "}",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Consumer(val clock: Clock)");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        var registry = result.generatedJavaNames().stream()
                .filter(name -> name.startsWith("io.tiko.generated.EventRegistry_"))
                .findFirst()
                .flatMap(result::generatedJava);
        assertThat(registry).hasValueSatisfying(source1 -> assertThat(source1).contains("onTick"));
    }
}
