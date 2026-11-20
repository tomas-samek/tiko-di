package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.KspProgram;
import org.junit.jupiter.api.Test;

/**
 * #837 on KSP: EVENT components that depend on each other through interfaces are destroyed
 * dependents first, so a component's EVENT dependencies are still usable during its cleanup. The
 * javac side, with an async handler and a carried unit, is {@code EventDestroyOrderTest}.
 */
class KspEventDestroyOrderTest {

    @Test
    void dependentsAreDestroyedBeforeTheirEventDependencies() throws Exception {
        var source = KspCompiler.kotlin(
                "Units.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.annotations.PreDestroy",
                "",
                "object Destroyed {",
                "    @JvmField val order = java.util.concurrent.CopyOnWriteArrayList<String>()",
                "}",
                "",
                "interface Connection { fun id(): Any }",
                "",
                "@Component(scope = Scope.EVENT)",
                "class PooledConnection : Connection {",
                "    private val id = Any()",
                "    override fun id(): Any = id",
                "    @PreDestroy fun release() { Destroyed.order.add(\"connection\") }",
                "}",
                "",
                "interface Transaction { fun connectionId(): Any }",
                "",
                "@Component(scope = Scope.EVENT)",
                "class ConnectionTransaction(private val connection: Connection) : Transaction {",
                "    override fun connectionId(): Any = connection.id()",
                "    @PreDestroy fun end() { Destroyed.order.add(\"transaction\") }",
                "}",
                "",
                "interface Audit {",
                "    fun touch()",
                "    fun connectionId(): Any",
                "}",
                "",
                "@Component(scope = Scope.EVENT)",
                "class TransactionAudit(private val transaction: Transaction) : Audit {",
                "    override fun touch() {}",
                "    override fun connectionId(): Any = transaction.connectionId()",
                "    @PreDestroy fun close() { Destroyed.order.add(\"audit\") }",
                "}",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Service(private val audit: Audit) {",
                "    /** Touches the audit first and reaches its connection only later in the unit. */",
                "    fun work() {",
                "        audit.touch()",
                "        audit.connectionId()",
                "    }",
                "}");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        try (var program = KspProgram.boot(result)) {
            var service = program.get("app.Service");
            program.runInEventScope(() -> {
                try {
                    service.getClass().getMethod("work").invoke(service);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("work failed", e);
                }
            });
            assertThat(program.staticList("app.Destroyed", "order"))
                    .as("destroy order: each component before the EVENT component it depends on")
                    .containsExactly("audit", "transaction", "connection");
        }
    }
}
