package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.Container;
import io.tiko.ContainerInitializationException;
import io.tiko.runtime.Tiko;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FailedBootstrapCleanupTest {

    static Stream<Exception> startupFailures() {
        return Stream.of(
                new IllegalStateException("unchecked startup failure"), new IOException("checked startup failure"));
    }

    @ParameterizedTest
    @MethodSource("startupFailures")
    void failedStartupClosesPreviouslyInitializedSingleton(Exception startupFailure, @TempDir Path output)
            throws Exception {
        compileProbe(output);
        var urls = new URL[] {
            output.toUri().toURL(),
            Tiko.class.getProtectionDomain().getCodeSource().getLocation(),
            Container.class.getProtectionDomain().getCodeSource().getLocation()
        };
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        var observerFailure = new AssertionError("teardown observer failure");
        // The single-module runtime loads the generated container with its own class loader.
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(loader);
            var result = (Object[]) loader.loadClass("bootstrap.BootstrapProbe")
                    .getMethod("run", Exception.class, Error.class)
                    .invoke(null, startupFailure, observerFailure);
            var thrown = (Throwable) result[0];
            if (startupFailure instanceof RuntimeException) {
                assertThat(thrown).isSameAs(startupFailure);
            } else {
                assertThat(thrown.getClass().getName()).isEqualTo(ContainerInitializationException.class.getName());
                assertThat(thrown).hasCause(startupFailure);
            }
            assertThat(result[1]).as("managed resource close calls").isEqualTo(1);
            assertThat(result[2]).as("application ending events").isEqualTo(0);
            assertThat(startupFailure.getSuppressed()).containsExactly(observerFailure);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void compileProbe(Path output) throws IOException {
        var source = JavaFileObjects.forSourceString("bootstrap.BootstrapProbe", """
                package bootstrap;

                import io.tiko.PreDestroyFailure;
                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.Inject;
                import io.tiko.annotations.PostConstruct;
                import io.tiko.annotations.PreDestroy;
                import io.tiko.events.ApplicationEndingEvent;
                import io.tiko.runtime.Tiko;
                import io.tiko.runtime.TikoOptions;
                import java.util.concurrent.atomic.AtomicInteger;

                public class BootstrapProbe {
                    private static Exception failure;
                    private static int closeCalls;

                    @Component(scope = Scope.SINGLETON)
                    public static class Resource implements AutoCloseable {
                        @Override
                        public void close() { closeCalls++; }
                    }

                    @Component(scope = Scope.SINGLETON)
                    public static class FailsTeardown {
                        @Inject
                        public FailsTeardown(Resource resource) {}

                        @PreDestroy
                        public void destroy() { throw new IllegalStateException("teardown failure"); }
                    }

                    @Component(scope = Scope.SINGLETON)
                    public static class FailsStartup {
                        @Inject
                        public FailsStartup(FailsTeardown dependency) {}

                        @PostConstruct
                        public void start() throws Exception { throw failure; }
                    }

                    public static Object[] run(Exception startupFailure, Error observerFailure) {
                        failure = startupFailure;
                        var endingEvents = new AtomicInteger();
                        var options = TikoOptions.builder()
                                .eventBusDecorator(bus -> {
                                    bus.subscribe(ApplicationEndingEvent.class, ignored -> endingEvents.incrementAndGet());
                                    return bus;
                                })
                                .errorHandler(context -> {
                                    if (context instanceof PreDestroyFailure) {
                                        throw observerFailure;
                                    }
                                })
                                .build();
                        try (var container = Tiko.create(options)) {
                            throw new AssertionError("Tiko.create returned a container after failed startup");
                        } catch (Exception thrown) {
                            return new Object[] {thrown, closeCalls, endingEvents.get()};
                        }
                    }
                }
                """);
        var compilation = Compiler.javac()
                .withOptions("--release", "21")
                .withProcessors(new TikoAnnotationProcessor())
                .compile(source);
        CompilationSubject.assertThat(compilation).succeeded();

        var classOutputPrefix = "/" + StandardLocation.CLASS_OUTPUT + "/";
        for (var generated : compilation.generatedFiles()) {
            var path = generated.toUri().getPath();
            if (path.startsWith(classOutputPrefix)) {
                var target = output.resolve(path.substring(classOutputPrefix.length()));
                Files.createDirectories(target.getParent());
                try (var contents = generated.openInputStream()) {
                    Files.copy(contents, target);
                }
            }
        }
    }
}
