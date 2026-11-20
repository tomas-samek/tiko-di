package io.tiko.examples.persistence.infra;

import io.tiko.Container;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Opens a Tiko EVENT scope (unit of work), resolves the {@link TransactionContext},
 * runs the user's work, and commits on success or rolls back on
 * exception. The batch entry point uses it, and the tests do. A route group that receives
 * requests itself takes {@code UnitOfWork} instead: see {@code tiko-examples/15_quickstart}.
 *
 * <p>Not a {@code @Component}: it depends on {@link Container} (not
 * DI-injectable) and is invoked at the framework boundary (the
 * batch loop and the tests).
 */
public final class TransactionalScope {

    private TransactionalScope() {}

    public static <T> T run(Container container, Supplier<T> work) {
        return container.supplyInEventScope(() -> {
            var tx = container.get(TransactionContext.class);
            try {
                T result = work.get();
                tx.commit();
                return result;
            } catch (RuntimeException e) {
                rollbackQuietly(tx, e);
                throw e;
            } catch (Throwable t) {
                rollbackQuietly(tx, t);
                throw new RuntimeException(t);
            }
        });
    }

    private static void rollbackQuietly(TransactionContext tx, Throwable original) {
        try {
            tx.rollback();
        } catch (SQLException sx) {
            original.addSuppressed(sx);
        }
    }
}
