package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

/**
 * #537: each module's descriptors are also written under a path of its own and the module is
 * listed as a {@code TikoModule} service, so a shaded fat jar — which keeps one copy of a
 * fixed-name resource but merges {@code META-INF/services} — still boots every module.
 */
class ModuleDescriptorEmissionTest {

    @Test
    void moduleDescriptorsLiveUnderTheirOwnPathAndTheModuleIsAService() throws IOException {
        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceLines(
                                "demo.Greeter",
                                "package demo;",
                                "import io.tiko.Scope;",
                                "import io.tiko.annotations.Component;",
                                "@Component(scope = Scope.SINGLETON)",
                                "public class Greeter {}"),
                        JavaFileObjects.forSourceLines(
                                "demo.AppConfig",
                                "package demo;",
                                "import io.tiko.annotations.Configuration;",
                                "@Configuration(prefix = \"app\") public record AppConfig(String name) {}"));
        CompilationSubject.assertThat(c).succeeded();

        String container = c.generatedSourceFiles().stream()
                .map(JavaFileObject::getName)
                .filter(n -> n.contains("TikoContainerImpl_"))
                .map(n -> n.substring(n.lastIndexOf('/') + 1, n.length() - ".java".length()))
                .findFirst()
                .orElseThrow();
        String root = "META-INF/tiko/modules/" + container + "/";
        String hash = container.substring(container.lastIndexOf('_') + 1);

        assertThat(read(c, root + "container.properties")).contains("impl=io.tiko.generated." + container);
        assertThat(read(c, root + "components.txt")).contains("demo.Greeter");
        assertThat(read(c, root + "configs.txt")).contains("demo.AppConfig=app");
        assertThat(read(c, "META-INF/services/io.tiko.TikoModule")).contains("io.tiko.generated.TikoModule_" + hash);

        JavaFileObject module = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().endsWith("TikoModule_" + hash + ".java"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoModule_" + hash + " not generated"));
        assertThat(new String(module.openInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .contains("implements TikoModule", "\"" + root + "\"", "@Generated");
    }

    private static String read(Compilation c, String path) throws IOException {
        var file = c.generatedFile(StandardLocation.CLASS_OUTPUT, path);
        assertThat(file).as(path + " generated").isPresent();
        return new String(file.get().openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
