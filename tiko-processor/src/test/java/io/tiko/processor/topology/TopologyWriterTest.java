package io.tiko.processor.topology;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

class TopologyWriterTest {

    @Test
    void richFixtureIsEmittedToMetaInfTikoTopologyJson() throws IOException {
        JavaFileObject service = JavaFileObjects.forSourceLines(
                "io.example.OrderService",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON)",
                "public class OrderService implements Orders {",
                "  @Inject public OrderService(OrderRepository repo) {}",
                "  @EventHandler public void onPlaced(OrderPlaced e) {}",
                "  @PostConstruct public void init() {}",
                "}");
        JavaFileObject ordersIface = JavaFileObjects.forSourceLines(
                "io.example.Orders", "package io.example;", "public interface Orders {}");
        JavaFileObject repo = JavaFileObjects.forSourceLines(
                "io.example.OrderRepository",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON)",
                "public class OrderRepository { @Inject public OrderRepository() {} }");
        JavaFileObject event = JavaFileObjects.forSourceLines(
                "io.example.OrderPlaced", "package io.example;", "public record OrderPlaced(String id) {}");
        JavaFileObject cfg = JavaFileObjects.forSourceLines(
                "io.example.DbConfig",
                "package io.example;",
                "import io.tiko.annotations.*;",
                "@Configuration(prefix = \"database\")",
                "public record DbConfig(String url, @Default(\"10\") int poolSize) {}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(service, ordersIface, repo, event, cfg);
        assertThat(c).succeeded();

        var fileOpt = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/topology.json");
        assertThat(fileOpt).isPresent();

        String json;
        try (var r = new InputStreamReader(fileOpt.get().openInputStream(), StandardCharsets.UTF_8)) {
            json = new java.io.BufferedReader(r).lines().reduce("", (acc, line) -> acc + line + "\n");
        }

        // Top-level
        assertThat(json).contains("\"schemaVersion\": 1");
        assertThat(json).contains("\"module\":");

        // Components
        assertThat(json).contains("\"qualifiedName\": \"io.example.OrderService\"");
        assertThat(json).contains("\"scope\": \"SINGLETON\"");
        assertThat(json).contains("\"interfaces\":");
        assertThat(json).contains("io.example.Orders");
        assertThat(json).contains("\"isTestComponent\": false");

        // Dependencies
        assertThat(json).contains("\"constructorDependencies\":");
        assertThat(json).contains("\"type\": \"io.example.OrderRepository\"");
        assertThat(json).contains("\"kind\": \"DIRECT\"");

        // Lifecycle
        assertThat(json).contains("\"postConstruct\":");
        assertThat(json).contains("\"init\"");
        assertThat(json).contains("\"autoCloseable\": false");

        // Event handlers
        assertThat(json).contains("\"eventHandlers\":");
        assertThat(json).contains("\"eventType\": \"io.example.OrderPlaced\"");
        assertThat(json).contains("\"async\": false");

        // Configurations
        assertThat(json).contains("\"configurations\":");
        assertThat(json).contains("\"prefix\": \"database\"");
        assertThat(json).contains("\"name\": \"url\"");
        assertThat(json).contains("\"cardinality\": \"REQUIRED\"");
        assertThat(json).contains("\"name\": \"poolSize\"");
        assertThat(json).contains("\"cardinality\": \"DEFAULTED\"");
        assertThat(json).contains("\"default\": \"10\"");
    }

    @Test
    void emptySourceSetDoesNotEmitTopologyJson() {
        // No @Component/@Produces/@EventHandler/@Configuration → no file.
        JavaFileObject plain =
                JavaFileObjects.forSourceLines("io.example.Plain", "package io.example;", "public class Plain {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(plain);

        var fileOpt = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/topology.json");
        assertThat(fileOpt).isNotPresent();
    }

    @Test
    void producesFactoryAppearsInFactoryMethodsArray() throws IOException {
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.DataSources",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON)",
                "public class DataSources {",
                "  @Inject public DataSources() {}",
                "  @Produces(scope = Scope.SINGLETON, name = \"mysql\")",
                "  public javax.sql.DataSource mysql() { return null; }",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(factory);
        assertThat(c).succeeded();

        var fileOpt = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/topology.json");
        assertThat(fileOpt).isPresent();
        String json;
        try (var r = new InputStreamReader(fileOpt.get().openInputStream(), StandardCharsets.UTF_8)) {
            json = new java.io.BufferedReader(r).lines().reduce("", (acc, line) -> acc + line + "\n");
        }
        assertThat(json).contains("\"factoryMethods\":");
        assertThat(json).contains("\"methodName\": \"mysql\"");
        assertThat(json).contains("\"qualifier\": \"mysql\"");
        assertThat(json).contains("\"returnType\": \"javax.sql.DataSource\"");
    }
}
