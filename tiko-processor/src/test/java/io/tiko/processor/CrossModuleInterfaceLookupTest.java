package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.runtime.Tiko;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class CrossModuleInterfaceLookupTest {

    public interface Service {
        String name();
    }

    @ParameterizedTest
    @CsvSource({"Alpha,Beta", "Beta,Alpha"})
    void unqualifiedLookupUsesFirstMatchingDiscoveredModule(String first, String second, @TempDir Path root)
            throws Exception {
        var urls = new URL[] {compileModule(root, first), compileModule(root, second)};
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            thread.setContextClassLoader(loader);
            try (var container = Tiko.create()) {
                assertThat(container.get(Service.class).name()).isEqualTo(first);
                assertThat(container.getProvider(Service.class).get().name()).isEqualTo(first);
                assertThat(container.getAll(Service.class))
                        .extracting(Service::name)
                        .containsExactlyInAnyOrder(first, second);

                var laterImplementation = loader.loadClass("lookup." + second).asSubclass(Service.class);
                assertThat(container.get(laterImplementation).name()).isEqualTo(second);
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }

    private static URL compileModule(Path root, String name) throws IOException {
        var source = JavaFileObjects.forSourceString("lookup." + name, """
                package lookup;
                import io.tiko.annotations.Component;
                import io.tiko.processor.CrossModuleInterfaceLookupTest.Service;

                @Component
                public class %1$s implements Service {
                    public String name() { return "%1$s"; }
                }
                """.formatted(name));
        var compilation = Compiler.javac()
                .withOptions("--release", "21")
                .withProcessors(new TikoAnnotationProcessor())
                .compile(source);
        CompilationSubject.assertThat(compilation).succeeded();

        var module = Files.createDirectory(root.resolve(name));
        // Materialize the generated module's classes and descriptors for ServiceLoader.
        var classOutputPrefix = "/" + StandardLocation.CLASS_OUTPUT + "/";
        for (var generated : compilation.generatedFiles()) {
            var path = generated.toUri().getPath();
            if (path.startsWith(classOutputPrefix)) {
                var target = module.resolve(path.substring(classOutputPrefix.length()));
                Files.createDirectories(target.getParent());
                try (var contents = generated.openInputStream()) {
                    Files.copy(contents, target);
                }
            }
        }
        return module.toUri().toURL();
    }
}
