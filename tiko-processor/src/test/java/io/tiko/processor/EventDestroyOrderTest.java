package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.JavaFileObjects;
import io.tiko.Container;
import io.tiko.runtime.Tiko;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #837: EVENT components that depend on each other through interfaces are destroyed in
 * reverse-creation order, dependents first, so a component's EVENT dependencies are still usable
 * during its cleanup. A chain of three ({@code TransactionAudit} &rarr; {@code ConnectionTransaction}
 * &rarr; {@code PooledConnection}), reached from a SINGLETON through a proxy, in a unit opened by
 * {@code runInEventScope}, by an async handler and by a carried unit.
 *
 * <p>The sources are compiled as a main module of their own and booted through a class loader.
 */
class EventDestroyOrderTest {

    private static final JavaFileObject[] SOURCES = {
        JavaFileObjects.forSourceString("destroying.DestroyLog", """
                package destroying;

                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import java.util.ArrayList;
                import java.util.List;

                @Component(scope = Scope.SINGLETON)
                public class DestroyLog {
                    private final List<String> entries = new ArrayList<>();

                    public synchronized void destroyed(String what) {
                        entries.add(what);
                        notifyAll();
                    }

                    /** The entries once there are {@code count} of them, waiting up to 5 s. */
                    public synchronized List<String> await(int count) throws InterruptedException {
                        long deadline = System.nanoTime() + 5_000_000_000L;
                        while (entries.size() < count && System.nanoTime() < deadline) {
                            wait(100);
                        }
                        return List.copyOf(entries);
                    }
                }
                """),
        JavaFileObjects.forSourceString("destroying.Connection", """
                package destroying;

                public interface Connection {
                    Object id();
                }
                """),
        JavaFileObjects.forSourceString("destroying.PooledConnection", """
                package destroying;

                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.Inject;
                import io.tiko.annotations.PreDestroy;

                @Component(scope = Scope.EVENT)
                public class PooledConnection implements Connection {
                    private final Object id = new Object();
                    private final DestroyLog log;

                    @Inject
                    public PooledConnection(DestroyLog log) {
                        this.log = log;
                    }

                    @Override
                    public Object id() {
                        return id;
                    }

                    @PreDestroy
                    public void release() {
                        log.destroyed("connection");
                    }
                }
                """),
        JavaFileObjects.forSourceString("destroying.Transaction", """
                package destroying;

                public interface Transaction {
                    Object connectionId();
                }
                """),
        JavaFileObjects.forSourceString("destroying.ConnectionTransaction", """
                package destroying;

                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.Inject;
                import io.tiko.annotations.PreDestroy;

                @Component(scope = Scope.EVENT)
                public class ConnectionTransaction implements Transaction {
                    private final Connection connection;
                    private final DestroyLog log;

                    @Inject
                    public ConnectionTransaction(Connection connection, DestroyLog log) {
                        this.connection = connection;
                        this.log = log;
                    }

                    @Override
                    public Object connectionId() {
                        return connection.id();
                    }

                    @PreDestroy
                    public void end() {
                        connection.id(); // its connection is still usable while it ends
                        log.destroyed("transaction");
                    }
                }
                """),
        JavaFileObjects.forSourceString("destroying.Audit", """
                package destroying;

                public interface Audit {
                    void touch();

                    Object connectionId();
                }
                """),
        JavaFileObjects.forSourceString("destroying.TransactionAudit", """
                package destroying;

                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.Inject;
                import io.tiko.annotations.PreDestroy;

                @Component(scope = Scope.EVENT)
                public class TransactionAudit implements Audit {
                    private final Transaction transaction;
                    private final DestroyLog log;

                    @Inject
                    public TransactionAudit(Transaction transaction, DestroyLog log) {
                        this.transaction = transaction;
                        this.log = log;
                    }

                    @Override
                    public void touch() {}

                    @Override
                    public Object connectionId() {
                        return transaction.connectionId();
                    }

                    @PreDestroy
                    public void close() {
                        log.destroyed("audit");
                    }
                }
                """),
        JavaFileObjects.forSourceString("destroying.Ping", """
                package destroying;

                public record Ping() {}
                """),
        JavaFileObjects.forSourceString("destroying.Service", """
                package destroying;

                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.EventHandler;
                import io.tiko.annotations.Inject;

                @Component(scope = Scope.SINGLETON)
                public class Service {
                    private final Audit audit;

                    @Inject
                    public Service(Audit audit) {
                        this.audit = audit;
                    }

                    /** Touches the audit first and reaches its connection only later in the unit. */
                    public void work() {
                        audit.touch();
                        audit.connectionId();
                    }

                    @EventHandler(async = true)
                    public void onPing(Ping ping) {
                        work();
                    }
                }
                """),
        JavaFileObjects.forSourceString("destroying.Probe", """
                package destroying;

                import io.tiko.Container;
                import io.tiko.runtime.Tiko;
                import io.tiko.runtime.UnitCarrier;
                import java.util.List;

                public class Probe {
                    /** What one unit, opened as {@code how} says, destroys, in order. */
                    public static List<String> run(String how) throws InterruptedException {
                        try (Container container = Tiko.create()) {
                            var service = container.get(Service.class);
                            var log = container.get(DestroyLog.class);
                            switch (how) {
                                case "runInEventScope" -> container.runInEventScope(service::work);
                                case "async handler" -> container.getEventBus().publish(new Ping());
                                case "carried unit" -> {
                                    var unit = ((UnitCarrier) container).openCarriedUnit(null);
                                    var displaced = unit.install();
                                    try {
                                        service.work();
                                    } finally {
                                        unit.close();
                                        unit.restore(displaced);
                                    }
                                }
                                default -> throw new IllegalArgumentException(how);
                            }
                            return log.await(3);
                        }
                    }
                }
                """),
    };

    @ParameterizedTest(name = "a unit opened by {0}")
    @ValueSource(strings = {"runInEventScope", "async handler", "carried unit"})
    void dependentsAreDestroyedBeforeTheirEventDependencies(String how, @TempDir Path output) throws Exception {
        assertThat(probe(how, output))
                .as("destroy order: each component before the EVENT component it depends on")
                .containsExactly("audit", "transaction", "connection");
    }

    @SuppressWarnings("unchecked")
    private static List<String> probe(String how, Path output) throws Exception {
        FailedBootstrapCleanupTest.compileModule(output, SOURCES);
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
            return (List<String>) loader.loadClass("destroying.Probe")
                    .getMethod("run", String.class)
                    .invoke(null, how);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
