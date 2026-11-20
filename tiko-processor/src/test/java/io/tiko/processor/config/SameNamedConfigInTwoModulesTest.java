package io.tiko.processor.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.config.ConfigSources;
import io.tiko.processor.TikoAnnotationProcessor;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #869: two modules that each declare a {@code @Configuration} record of the same simple name, in
 * packages of their own, boot as one application and each gets its record bound.
 */
class SameNamedConfigInTwoModulesTest {

    @Test
    void eachModuleGetsItsOwnSameNamedRecord(@TempDir Path root) throws Exception {
        var urls = new URL[] {compileModule(root, "alpha"), compileModule(root, "beta")};
        var options = TikoOptions.builder()
                .configSource(ConfigSources.fromMap(
                        Map.of("alpha", Map.of("name", "from-alpha"), "beta", Map.of("name", "from-beta"))))
                .build();

        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            thread.setContextClassLoader(loader);
            try (var container = Tiko.create(options)) {
                for (var pkg : new String[] {"alpha", "beta"}) {
                    var reader = container.get(loader.loadClass(pkg + ".SettingsReader"));
                    assertThat(((Supplier<?>) reader).get())
                            .as("the settings module %s's component was injected", pkg)
                            .isEqualTo("from-" + pkg);
                }
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }

    /** A module of a {@code Settings} record under its own prefix, and a component that reads it. */
    private static URL compileModule(Path root, String pkg) throws IOException {
        var settings = JavaFileObjects.forSourceString(pkg + ".Settings", """
                package %1$s;
                import io.tiko.annotations.Configuration;

                @Configuration(prefix = "%1$s")
                public record Settings(String name) {}
                """.formatted(pkg));
        var reader = JavaFileObjects.forSourceString(pkg + ".SettingsReader", """
                package %1$s;
                import io.tiko.annotations.Component;
                import java.util.function.Supplier;

                @Component
                public class SettingsReader implements Supplier<String> {
                    private final Settings settings;
                    public SettingsReader(Settings settings) { this.settings = settings; }
                    public String get() { return settings.name(); }
                }
                """.formatted(pkg));
        var compilation = Compiler.javac()
                .withOptions("--release", "21")
                .withProcessors(new TikoAnnotationProcessor())
                .compile(settings, reader);
        CompilationSubject.assertThat(compilation).succeeded();

        var module = Files.createDirectory(root.resolve(pkg));
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
