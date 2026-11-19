package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.JavaFileObjects;
import io.tiko.Container;
import io.tiko.runtime.Tiko;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #845: a value a {@code @Produces} method returns is closed when it is {@code AutoCloseable},
 * whatever type the method declares: a {@code DataSource} producer that returns a
 * {@code HikariDataSource} is the usual case.
 *
 * <p>The sources are compiled as a main module of their own and booted through a class loader:
 * only there are singletons constructed at container start.
 */
class ProducedCloseableTeardownTest {

    private static final JavaFileObject[] MODULE = {
        JavaFileObjects.forSourceString("closing.Pool", """
                package closing;

                /** Declared type of the produced values: not AutoCloseable itself. */
                public interface Pool {}
                """),
        JavaFileObjects.forSourceString("closing.ClosingPool", """
                package closing;

                import java.util.Map;
                import java.util.concurrent.ConcurrentHashMap;
                import java.util.concurrent.atomic.AtomicInteger;

                public class ClosingPool implements Pool, AutoCloseable {
                    public static final Map<String, AtomicInteger> CLOSED = new ConcurrentHashMap<>();
                    private final String name;

                    public ClosingPool(String name) {
                        this.name = name;
                    }

                    @Override
                    public void close() {
                        CLOSED.computeIfAbsent(name, ignored -> new AtomicInteger()).incrementAndGet();
                    }
                }
                """),
        JavaFileObjects.forSourceString("closing.Lease", """
                package closing;

                public interface Lease {}
                """),
        JavaFileObjects.forSourceString("closing.ClosingLease", """
                package closing;

                public class ClosingLease extends ClosingPool implements Lease {
                    public ClosingLease() {
                        super("event");
                    }
                }
                """),
        JavaFileObjects.forSourceString("closing.Resources", """
                package closing;

                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.Produces;

                @Component(scope = Scope.SINGLETON)
                public class Resources {
                    @Produces(scope = Scope.SINGLETON)
                    public Pool pool() {
                        return new ClosingPool("singleton");
                    }

                    @Produces(scope = Scope.EVENT)
                    public Lease lease() {
                        return new ClosingLease();
                    }
                }
                """),
        JavaFileObjects.forSourceString("closing.StartsAfterThePool", """
                package closing;

                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.Inject;
                import io.tiko.annotations.PostConstruct;

                @Component(scope = Scope.SINGLETON)
                public class StartsAfterThePool {
                    public static volatile boolean fail;

                    @Inject
                    public StartsAfterThePool(Pool pool) {}

                    @PostConstruct
                    public void start() {
                        if (fail) throw new IllegalStateException("boot fails after the pool was produced");
                    }
                }
                """),
        JavaFileObjects.forSourceString("closing.Probe", """
                package closing;

                import io.tiko.Container;
                import io.tiko.runtime.Tiko;
                import java.util.Map;
                import java.util.TreeMap;

                public class Probe {
                    /** Boots, opens one unit that resolves the lease, closes; answers the close counts. */
                    public static Map<String, Integer> run(boolean failBoot) {
                        StartsAfterThePool.fail = failBoot;
                        try (Container container = Tiko.create()) {
                            container.runInEventScope(() -> container.get(Lease.class));
                        } catch (RuntimeException expectedWhenTheBootFails) {
                            if (!failBoot) throw expectedWhenTheBootFails;
                        }
                        var closed = new TreeMap<String, Integer>();
                        ClosingPool.CLOSED.forEach((name, count) -> closed.put(name, count.get()));
                        return closed;
                    }
                }
                """),
    };

    static Stream<Arguments> boots() {
        return Stream.of(
                Arguments.of("a boot and close()", false, Map.of("singleton", 1, "event", 1)),
                Arguments.of("a boot that fails after the pool was produced", true, Map.of("singleton", 1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boots")
    void aProducedAutoCloseableIsClosedWhateverTypeItsProducerDeclares(
            String name, boolean failBoot, Map<String, Integer> expected, @TempDir Path output) throws Exception {
        assertThat(boot(failBoot, output))
                .as("close() calls per produced value")
                .isEqualTo(expected);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> boot(boolean failBoot, Path output) throws Exception {
        FailedBootstrapCleanupTest.compileModule(output, MODULE);
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
            return (Map<String, Integer>) loader.loadClass("closing.Probe")
                    .getMethod("run", boolean.class)
                    .invoke(null, failBoot);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
