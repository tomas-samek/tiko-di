package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.KspProgram;
import java.util.List;
import org.junit.jupiter.api.Test;

/** #685 on KSP: a Kotlin component takes {@code UnitOfWork} and opens a unit per call. */
class KspUnitOfWorkInjectionTest {

    private static com.tschuchort.compiletesting.SourceFile routes() {
        return KspCompiler.kotlin(
                "Routes.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.UnitOfWork",
                "import io.tiko.annotations.Component",
                "",
                "interface RequestId { fun value(): Any }",
                "",
                "@Component(scope = Scope.EVENT)",
                "class FreshRequestId : RequestId {",
                "    private val id = Any()",
                "    override fun value(): Any = id",
                "}",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Routes(private val unitOfWork: UnitOfWork, private val requestId: RequestId) {",
                "    /** The request id seen twice inside one unit of work. */",
                "    fun handle(): List<Any> = unitOfWork.supplyInEventScope { listOf(requestId.value(), requestId.value()) }",
                "}");
    }

    @Test
    void kotlinComponentTakesUnitOfWork() {
        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), routes());

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        assertThat(result.generatedJavaNames().stream()
                        .filter(name -> name.startsWith("io.tiko.generated.RoutesFactory_"))
                        .findFirst()
                        .flatMap(result::generatedJava))
                .hasValueSatisfying(factory -> assertThat(factory)
                        .contains("new io.tiko.runtime.ContainerUnitOfWork(container, container.options())"));
    }

    @Test
    void eachCallThroughTheInjectedUnitOfWorkRunsInItsOwnUnit() throws Exception {
        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), routes());
        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);

        try (var program = KspProgram.boot(result)) {
            var routes = program.get("app.Routes");
            var handle = routes.getClass().getMethod("handle");
            var first = (List<?>) handle.invoke(routes);
            var second = (List<?>) handle.invoke(routes);

            assertThat(first.get(0)).as("one unit, one instance").isSameAs(first.get(1));
            assertThat(second.get(0)).as("another call, another unit").isNotSameAs(first.get(0));
        }
    }
}
