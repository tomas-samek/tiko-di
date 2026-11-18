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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #677 on a generated container: a carried unit of work keeps its EVENT-scoped instances from
 * thread to thread, leaves a thread's own unit as it was, and closes once.
 *
 * <p>The sources are compiled and booted through a class loader; {@code carried.Probe} drives the
 * container there and answers what it saw.
 */
class CarriedUnitBootTest {

    private static final JavaFileObject[] SOURCES = {
        JavaFileObjects.forSourceString("carried.DestroyLog", """
                package carried;

                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import java.util.List;
                import java.util.concurrent.CopyOnWriteArrayList;

                @Component(scope = Scope.SINGLETON)
                public class DestroyLog {
                    private final List<String> destroyed = new CopyOnWriteArrayList<>();

                    public void destroyed(String what) {
                        destroyed.add(what);
                    }

                    public List<String> entries() {
                        return List.copyOf(destroyed);
                    }
                }
                """),
        JavaFileObjects.forSourceString("carried.Connection", """
                package carried;

                public interface Connection {
                    Object id();
                }
                """),
        JavaFileObjects.forSourceString("carried.PooledConnection", """
                package carried;

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
        JavaFileObjects.forSourceString("carried.Transaction", """
                package carried;

                public interface Transaction {
                    Object connectionId();
                }
                """),
        JavaFileObjects.forSourceString("carried.ConnectionTransaction", """
                package carried;

                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.Inject;
                import io.tiko.annotations.PreDestroy;

                /** Created after the connection it uses, so destroyed before it. */
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
                        log.destroyed("transaction");
                    }
                }
                """),
        JavaFileObjects.forSourceString("carried.Service", """
                package carried;

                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.Inject;

                /** A SINGLETON that reaches the current unit's instances through proxies. */
                @Component(scope = Scope.SINGLETON)
                public class Service {
                    private final Transaction transaction;

                    @Inject
                    public Service(Transaction transaction) {
                        this.transaction = transaction;
                    }

                    /** Identifies the unit of work the caller is in. */
                    public Object unit() {
                        return transaction.connectionId();
                    }
                }
                """),
        JavaFileObjects.forSourceString("carried.Probe", """
                package carried;

                import io.tiko.Container;
                import io.tiko.NoActiveEventScopeException;
                import io.tiko.events.EventEndingEvent;
                import io.tiko.events.EventStartedEvent;
                import io.tiko.runtime.CarriedUnit;
                import io.tiko.runtime.Tiko;
                import io.tiko.runtime.UnitCarrier;
                import java.util.List;
                import java.util.concurrent.CopyOnWriteArrayList;

                public class Probe {

                    private static Object unitSeenOnAnotherThread(CarriedUnit unit, Service service)
                            throws InterruptedException {
                        var seen = new Object[1];
                        var thread = new Thread(() -> {
                            var displaced = unit.install();
                            try {
                                seen[0] = service.unit();
                            } finally {
                                unit.restore(displaced);
                            }
                        });
                        thread.start();
                        thread.join();
                        return seen[0];
                    }

                    /** The unit seen on two threads in turn, and what the opening thread sees without it. */
                    public static Object[] fromThreadToThread() throws InterruptedException {
                        try (Container container = Tiko.create()) {
                            var service = container.get(Service.class);
                            var unit = ((UnitCarrier) container).openCarriedUnit(null);
                            Object first = unitSeenOnAnotherThread(unit, service);
                            Object second = unitSeenOnAnotherThread(unit, service);
                            String without;
                            try {
                                service.unit();
                                without = "reachable";
                            } catch (NoActiveEventScopeException expected) {
                                without = "no unit of work";
                            }
                            unit.close();
                            return new Object[] {first, second, without};
                        }
                    }

                    /** A thread inside a unit of its own: its unit, the carried one, its unit again. */
                    public static Object[] aroundAThreadsOwnUnit() {
                        try (Container container = Tiko.create()) {
                            var service = container.get(Service.class);
                            var unit = ((UnitCarrier) container).openCarriedUnit(null);
                            return container.supplyInEventScope(() -> {
                                Object own = service.unit();
                                var displaced = unit.install();
                                Object carried = service.unit();
                                unit.close();
                                unit.restore(displaced);
                                return new Object[] {own, carried, service.unit()};
                            });
                        }
                    }

                    /**
                     * What an ordinary unit destroys, what the carried one destroys after it, the carried
                     * unit's lifecycle events, and whether a unit can be opened under the carried one.
                     */
                    public static Object[] closing() {
                        try (Container container = Tiko.create()) {
                            container.runInEventScope(() -> container.get(Service.class).unit());
                            List<String> ordinary = container.get(DestroyLog.class).entries();
                            var lifecycle = new CopyOnWriteArrayList<String>();
                            container.getEventBus().subscribe(
                                    EventStartedEvent.class, started -> lifecycle.add("started " + started.eventId()));
                            container.getEventBus().subscribe(
                                    EventEndingEvent.class, ending -> lifecycle.add("ending " + ending.eventId()));
                            var service = container.get(Service.class);
                            var log = container.get(DestroyLog.class);
                            var unit = ((UnitCarrier) container).openCarriedUnit(null);

                            var displaced = unit.install();
                            service.unit();
                            String nested;
                            try {
                                container.runInEventScope(() -> {});
                                nested = "opened";
                            } catch (IllegalStateException expected) {
                                nested = "rejected";
                            }
                            List<String> beforeClose = log.entries();
                            unit.close();
                            unit.restore(displaced);
                            unit.close();
                            return new Object[] {beforeClose, log.entries(), List.copyOf(lifecycle), nested, ordinary};
                        }
                    }
                }
                """),
    };

    private static Object[] probe(String scenario, Path output) throws Exception {
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
            return (Object[])
                    loader.loadClass("carried.Probe").getMethod(scenario).invoke(null);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void eventInstancesFollowTheUnitFromThreadToThread(@TempDir Path output) throws Exception {
        var seen = probe("fromThreadToThread", output);

        assertThat(seen[0]).as("the unit's instance on the first thread").isNotNull();
        assertThat(seen[1]).as("the same instance on the second thread").isSameAs(seen[0]);
        assertThat(seen[2]).as("a thread the unit is not on").isEqualTo("no unit of work");
    }

    @Test
    void aThreadsOwnUnitIsBackAfterTheCarriedOneIsTakenOff(@TempDir Path output) throws Exception {
        var seen = probe("aroundAThreadsOwnUnit", output);

        assertThat(seen[1]).as("the carried unit's instance").isNotNull().isNotSameAs(seen[0]);
        assertThat(seen[2]).as("the thread's own instance again").isSameAs(seen[0]);
    }

    @Test
    void closingTearsTheUnitDownOnceAndPublishesOneLifecyclePair(@TempDir Path output) throws Exception {
        var seen = probe("closing", output);

        var ordinary = (List<?>) seen[4];
        var twice = new java.util.ArrayList<Object>(ordinary);
        twice.addAll(ordinary);
        assertThat(ordinary).as("an ordinary unit runs both destroy hooks").hasSize(2);
        assertThat(seen[0])
                .as("nothing of the carried unit is destroyed before close")
                .isEqualTo(ordinary);
        assertThat(seen[1])
                .as("the carried unit runs the destroy hooks in an ordinary unit's order, once")
                .isEqualTo(twice);
        var lifecycle = ((List<?>) seen[2]).stream().map(Object::toString).toList();
        assertThat(lifecycle).hasSize(2);
        String unit = lifecycle.get(0).substring("started ".length());
        assertThat(lifecycle).containsExactly("started " + unit, "ending " + unit);
        assertThat(seen[3])
                .as("runInEventScope while the carried unit is installed (ARCH-5)")
                .isEqualTo("rejected");
    }
}
