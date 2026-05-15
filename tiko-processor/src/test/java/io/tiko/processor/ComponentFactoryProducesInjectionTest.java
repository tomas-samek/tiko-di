package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for: a {@code @Component} whose {@code @Inject}
 * constructor takes a dependency that is provided by a {@code @Produces}
 * factory method.
 *
 * <p>Bug found while building {@code tiko-examples/10_persistence_jdbc}:
 * {@code ComponentFactoryGenerator.generateContainerGetCall} emitted
 * {@code container.getDataSource()} for the factory-provided dependency,
 * but {@code ContainerGenerator} exposes that accessor as
 * {@code container.produce_<factoryId>()}. The generated
 * {@code <Consumer>Factory.java} referenced a method that didn't exist
 * on the container — javac compile error after annotation processing.
 *
 * <p>The {@code @Pick} branch above the broken site already uses the
 * correct {@code produce_<id>()} form; this test pins down the non-pick
 * branch to the same shape.
 */
class ComponentFactoryProducesInjectionTest {

    @Test
    void componentFactoryCallsProduceAccessorForProducesProvidedDependency() throws IOException {
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.PoolFactory",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "import javax.sql.DataSource;",
                "@Component(scope = Scope.SINGLETON)",
                "public class PoolFactory {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  public DataSource ds() { return null; }",
                "}");

        JavaFileObject consumer = JavaFileObjects.forSourceLines(
                "io.example.PoolConsumer",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "import javax.sql.DataSource;",
                "@Component(scope = Scope.SINGLETON)",
                "public class PoolConsumer {",
                "  @Inject",
                "  public PoolConsumer(DataSource ds) {}",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(factory, consumer);

        // The whole point: compilation must succeed. Pre-fix, javac fails on
        // the generated PoolConsumerFactory because container.getDataSource()
        // is referenced but doesn't exist on the generated container.
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject consumerFactorySource = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("PoolConsumerFactory"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PoolConsumerFactory was not generated"));

        String body = new String(consumerFactorySource.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // The dependency must be resolved via the produce_ accessor — never via
        // a synthetic get<Type>() that doesn't exist on the container.
        assertThat(body).contains("container.produce_");
        assertThat(body).doesNotContain("container.getDataSource()");
    }
}
