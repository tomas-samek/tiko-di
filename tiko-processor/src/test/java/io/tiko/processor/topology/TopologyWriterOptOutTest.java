package io.tiko.processor.topology;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

class TopologyWriterOptOutTest {

    @Test
    void optOutSuppressesTopologyJson() {
        var component = JavaFileObjects.forSourceLines(
                "io.example.X",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON) public class X { @Inject public X() {} }");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .withOptions("-Atiko.topology.bundle=false")
                .compile(component);
        assertThat(c).succeeded();

        assertThat(c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/topology.json"))
                .isNotPresent();
    }

    @Test
    void defaultEmitsTopologyJson() {
        var component = JavaFileObjects.forSourceLines(
                "io.example.X",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON) public class X { @Inject public X() {} }");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component);
        assertThat(c).succeeded();

        assertThat(c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/topology.json"))
                .isPresent();
    }
}
