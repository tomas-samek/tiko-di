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
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class FailedBootstrapCleanupTest {

    static Stream<Exception> startupFailures() {
        return Stream.of(
                new IllegalStateException("unchecked startup failure"), new IOException("checked startup failure"));
    }

    @ParameterizedTest
    @MethodSource("startupFailures")
    @NullSource
    void singletonCleanupPreservesPrimaryFailure(Exception startupFailure, @TempDir Path output) throws Exception {
        compileProbe(output);
        Throwable observerFailure = startupFailure == null
                ? new IllegalStateException("teardown observer failure")
                : new AssertionError("teardown observer failure");
        var result = runProbe(startupFailure, observerFailure, null, output);
        var thrown = (Throwable) result[0];
        if (startupFailure == null) {
            assertThat(thrown).isSameAs(observerFailure);
            assertThat(result[2]).as("application ending events").isEqualTo(1);
            assertThat(result[3]).as("framework-owned executor shut down").isEqualTo(true);
        } else if (startupFailure instanceof RuntimeException) {
            assertThat(thrown).isSameAs(startupFailure);
        } else {
            assertThat(thrown.getClass().getName()).isEqualTo(ContainerInitializationException.class.getName());
            assertThat(thrown).hasCause(startupFailure);
        }
        assertThat(result[1]).as("managed resource close calls").isEqualTo(1);
        assertThat(observerFailure.getSuppressed()).containsExactly((Throwable) result[4]);
        if (startupFailure != null) {
            assertThat(result[2]).as("application ending events").isEqualTo(0);
            assertThat(startupFailure.getSuppressed()).containsExactly(observerFailure);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aggregateShutdownCompletesOtherModulesBeforePropagatingObserverFailure(
            boolean observerThrowsError, @TempDir Path output) throws Exception {
        var first = Files.createDirectory(output.resolve("first"));
        var second = Files.createDirectory(output.resolve("second"));
        compileModule(first, JavaFileObjects.forSourceString("peer.PeerResource", """
                package peer;
                import io.tiko.Scope;
                import io.tiko.annotations.Component;

                @Component(scope = Scope.SINGLETON)
                public class PeerResource implements AutoCloseable {
                    private static int closeCalls;

                    @Override
                    public void close() { closeCalls++; }

                    public static int closed() { return closeCalls; }
                }
                """));
        compileProbe(second);
        Throwable observerFailure = observerThrowsError
                ? new AssertionError("teardown observer failure")
                : new IllegalStateException("teardown observer failure");

        var result = runProbe(null, observerFailure, "peer.PeerResource", first, second);

        assertThat(result[0]).isSameAs(observerFailure);
        assertThat(observerFailure.getSuppressed()).containsExactly((Throwable) result[4]);
        assertThat(result[1]).as("resource closed in failing module").isEqualTo(1);
        assertThat(result[3]).as("shared executor shut down").isEqualTo(true);
        assertThat(result[5]).as("resource closed in other module").isEqualTo(1);
    }

    private static Object[] runProbe(
            Exception startupFailure, Throwable observerFailure, String peerResource, Path... modules)
            throws Exception {
        var urls = new URL[modules.length + 2];
        for (int i = 0; i < modules.length; i++) {
            urls[i] = modules[i].toUri().toURL();
        }
        urls[modules.length] = Tiko.class.getProtectionDomain().getCodeSource().getLocation();
        urls[modules.length + 1] =
                Container.class.getProtectionDomain().getCodeSource().getLocation();
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        // The single-module runtime loads the generated container with its own class loader.
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(loader);
            return (Object[]) loader.loadClass("bootstrap.BootstrapProbe")
                    .getMethod("run", Exception.class, Throwable.class, String.class)
                    .invoke(null, startupFailure, observerFailure, peerResource);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void compileProbe(Path output) throws IOException {
        var probe = JavaFileObjects.forSourceString("bootstrap.BootstrapProbe", """
                package bootstrap;

                import io.tiko.PreDestroyFailure;
                import io.tiko.events.ApplicationEndingEvent;
                import io.tiko.runtime.Tiko;
                import io.tiko.runtime.TikoOptions;
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.atomic.AtomicInteger;

                public class BootstrapProbe {
                    static final IllegalStateException TEARDOWN_FAILURE =
                            new IllegalStateException("teardown failure");
                    static Exception failure;

                    public static Object[] run(Exception startupFailure, Throwable observerFailure, String peerResource)
                            throws ReflectiveOperationException {
                        failure = startupFailure;
                        var endingEvents = new AtomicInteger();
                        var options = TikoOptions.builder()
                                .eventBusDecorator(bus -> {
                                    bus.subscribe(ApplicationEndingEvent.class, ignored -> endingEvents.incrementAndGet());
                                    return bus;
                                })
                                .errorHandler(context -> {
                                    if (context instanceof PreDestroyFailure) {
                                        if (observerFailure instanceof RuntimeException exception) throw exception;
                                        throw (Error) observerFailure;
                                    }
                                })
                                .build();
                        ExecutorService executor = null;
                        var peer = peerResource == null ? null : Class.forName(peerResource);
                        try (var container = Tiko.create(options)) {
                            executor = container.getEventExecutor();
                            if (peer != null) {
                                container.get(peer);
                                container.get(FailsTeardown.class);
                            }
                            if (startupFailure != null) {
                                throw new AssertionError("Tiko.create returned a container after failed startup");
                            }
                        } catch (Throwable thrown) {
                            if (executor == null && startupFailure == null) {
                                throw new AssertionError("probe could not boot", thrown);
                            }
                            return new Object[] {
                                thrown, Resource.closed(), endingEvents.get(), executor != null && executor.isShutdown(),
                                TEARDOWN_FAILURE, peer == null ? null : peer.getMethod("closed").invoke(null)
                            };
                        }
                        throw new AssertionError("expected a shutdown failure");
                    }
                }
                """);
        var resource = JavaFileObjects.forSourceString("bootstrap.Resource", """
                package bootstrap;
                import io.tiko.Scope;
                import io.tiko.annotations.Component;

                @Component(scope = Scope.SINGLETON)
                public class Resource implements AutoCloseable {
                    private static int closeCalls;

                    @Override
                    public void close() { closeCalls++; }

                    public static int closed() { return closeCalls; }
                }
                """);
        var failsTeardown = JavaFileObjects.forSourceString("bootstrap.FailsTeardown", """
                package bootstrap;
                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.Inject;
                import io.tiko.annotations.PreDestroy;

                @Component(scope = Scope.SINGLETON)
                public class FailsTeardown {
                    @Inject
                    public FailsTeardown(Resource resource) {}

                    @PreDestroy
                    public void destroy() { throw BootstrapProbe.TEARDOWN_FAILURE; }
                }
                """);
        var failsStartup = JavaFileObjects.forSourceString("bootstrap.FailsStartup", """
                package bootstrap;
                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.Inject;
                import io.tiko.annotations.PostConstruct;

                @Component(scope = Scope.SINGLETON)
                public class FailsStartup {
                    @Inject
                    public FailsStartup(FailsTeardown dependency) {}

                    @PostConstruct
                    public void start() throws Exception {
                        if (BootstrapProbe.failure != null) throw BootstrapProbe.failure;
                    }
                }
                """);
        compileModule(output, probe, resource, failsTeardown, failsStartup);
    }

    private static void compileModule(Path output, JavaFileObject... sources) throws IOException {
        var compilation = Compiler.javac()
                .withOptions("--release", "21")
                .withProcessors(new TikoAnnotationProcessor())
                .compile(sources);
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
