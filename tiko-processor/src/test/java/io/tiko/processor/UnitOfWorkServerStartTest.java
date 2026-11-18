package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.JavaFileObjects;
import io.tiko.Container;
import io.tiko.ContainerInitializationException;
import io.tiko.runtime.Tiko;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #685: a server that takes the built-in {@code UnitOfWork} is a component like any other, so it
 * starts in {@code @PostConstruct}, inside the container lifecycle. A start that fails (its port
 * is taken) fails the boot, and what the container had started is shut down.
 *
 * <p>The sources are compiled as a main module of their own and booted through a class loader:
 * only there are singletons constructed at container start.
 */
class UnitOfWorkServerStartTest {

    private static final JavaFileObject[] SERVER = {
        JavaFileObjects.forSourceString("server.Port", """
                package server;

                /** The port the server binds; 0 picks a free one. */
                public class Port {
                    public static volatile int value;
                }
                """),
        JavaFileObjects.forSourceString("server.StartedFirst", """
                package server;

                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.PreDestroy;
                import java.util.concurrent.atomic.AtomicInteger;

                @Component(scope = Scope.SINGLETON)
                public class StartedFirst {
                    public static final AtomicInteger SHUT_DOWN = new AtomicInteger();

                    @PreDestroy
                    public void shutDown() {
                        SHUT_DOWN.incrementAndGet();
                    }
                }
                """),
        JavaFileObjects.forSourceString("server.RequestId", """
                package server;

                public interface RequestId {
                    Object value();
                }
                """),
        JavaFileObjects.forSourceString("server.FreshRequestId", """
                package server;

                import io.tiko.Scope;
                import io.tiko.annotations.Component;

                @Component(scope = Scope.EVENT)
                public class FreshRequestId implements RequestId {
                    private final Object value = new Object();

                    @Override
                    public Object value() {
                        return value;
                    }
                }
                """),
        JavaFileObjects.forSourceString("server.PortBoundServer", """
                package server;

                import io.tiko.Scope;
                import io.tiko.UnitOfWork;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.Inject;
                import io.tiko.annotations.PostConstruct;
                import io.tiko.annotations.PreDestroy;
                import java.io.IOException;
                import java.net.ServerSocket;

                @Component(scope = Scope.SINGLETON)
                public class PortBoundServer {
                    private final UnitOfWork unitOfWork;
                    private final RequestId requestId;
                    private ServerSocket socket;

                    @Inject
                    public PortBoundServer(UnitOfWork unitOfWork, StartedFirst startedFirst, RequestId requestId) {
                        this.unitOfWork = unitOfWork;
                        this.requestId = requestId;
                    }

                    @PostConstruct
                    public void start() throws IOException {
                        socket = new ServerSocket(Port.value);
                    }

                    @PreDestroy
                    public void stop() throws IOException {
                        socket.close();
                    }

                    /** What a route does: one request, one unit of work. */
                    public Object handle() {
                        return unitOfWork.supplyInEventScope(requestId::value);
                    }
                }
                """),
        JavaFileObjects.forSourceString("server.ServerProbe", """
                package server;

                import io.tiko.Container;
                import io.tiko.runtime.Tiko;
                import io.tiko.runtime.TikoOptions;
                import java.util.List;
                import java.util.concurrent.CopyOnWriteArrayList;

                public class ServerProbe {
                    /** Boots on {@code port}; answers the boot failure, two handled requests, and leaked threads. */
                    public static Object[] run(int port) {
                        Port.value = port;
                        List<String> leaked = new CopyOnWriteArrayList<>();
                        var options = TikoOptions.builder()
                                .onThreadsOutlivingClose(threads -> threads.forEach(t -> leaked.add(t.getName())))
                                .build();
                        Throwable failure = null;
                        Object first = null;
                        Object second = null;
                        try (Container container = Tiko.create(options)) {
                            var server = container.get(PortBoundServer.class);
                            first = server.handle();
                            second = server.handle();
                        } catch (Throwable thrown) {
                            failure = thrown;
                        }
                        return new Object[] {failure, first, second, StartedFirst.SHUT_DOWN.get(), List.copyOf(leaked)};
                    }
                }
                """),
    };

    private static Object[] bootOn(int port, Path output) throws Exception {
        FailedBootstrapCleanupTest.compileModule(output, SERVER);
        var urls = new URL[] {
            output.toUri().toURL(),
            Tiko.class.getProtectionDomain().getCodeSource().getLocation(),
            Container.class.getProtectionDomain().getCodeSource().getLocation(),
        };
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        // The single-module runtime loads the generated container with its own class loader.
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(loader);
            return (Object[]) loader.loadClass("server.ServerProbe")
                    .getMethod("run", int.class)
                    .invoke(null, port);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void aServerThatCannotStartFailsTheBootAndWhatStartedIsShutDown(@TempDir Path output) throws Exception {
        try (var taken = new ServerSocket(0)) {
            var result = bootOn(taken.getLocalPort(), output);

            var failure = (Throwable) result[0];
            assertThat(failure).as("Tiko.create(...) throws").isNotNull().hasCauseInstanceOf(BindException.class);
            assertThat(failure.getClass().getName()).isEqualTo(ContainerInitializationException.class.getName());
            assertThat(result[3]).as("StartedFirst was shut down").isEqualTo(1);
            assertThat((List<?>) result[4])
                    .as("threads outliving the failed boot")
                    .isEmpty();
        }
    }

    @Test
    void aServerThatStartsHandlesEachRequestInItsOwnUnit(@TempDir Path output) throws Exception {
        var result = bootOn(0, output);

        assertThat(result[0]).as("boot failure").isNull();
        assertThat(result[1]).as("first request's EVENT instance").isNotNull();
        assertThat(result[2]).as("second request, another unit").isNotNull().isNotSameAs(result[1]);
        assertThat(result[3]).as("StartedFirst was shut down by close()").isEqualTo(1);
    }
}
