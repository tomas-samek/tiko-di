package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import org.junit.jupiter.api.Test;

/** The DI processor runs on KSP2 over Kotlin sources and its generated Java compiles in the same run. */
class KspBootTest {

    @Test
    void kotlinComponentGraphGeneratesContainer() {
        var source = KspCompiler.kotlin(
                "Graph.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Repository",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Service(val repository: Repository)");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        var container = result.generatedJavaNames().stream()
                .filter(name -> name.startsWith("io.tiko.generated.TikoContainerImpl_"))
                .findFirst()
                .flatMap(result::generatedJava);
        assertThat(container).as("generated container").isPresent();
        assertThat(container.get()).contains("ServiceFactory").contains("RepositoryFactory");
        assertThat(result.generatedJava("io.tiko.generated.ServiceFactory"))
                .hasValueSatisfying(factory -> assertThat(factory).contains("new Service("));
    }
}
