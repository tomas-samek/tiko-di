package io.tiko.examples.quickstart;

import io.javalin.http.Handler;
import io.tiko.Provider;
import io.tiko.Scope;
import io.tiko.UnitOfWork;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.sql.SQLException;

/**
 * Runs a Javalin handler in a unit of work and a database transaction of its own: one of each
 * per request. A route group wraps each of its handlers with {@link #inTransaction}, because
 * Javalin 7 has no handler that wraps a request.
 *
 * <p>The transaction commits when the handler returns, and rolls back when it throws, whatever
 * it had written: Javalin's own responses ({@code BadRequestResponse}, ...) pass on as they are,
 * and Javalin turns them into the response. {@link TransactionContext} is EVENT-scoped and has no
 * interface to proxy, so this SINGLETON takes it as a {@link Provider} and resolves it inside
 * the unit.
 */
@Component(scope = Scope.SINGLETON)
public class RequestTransactions {

    private final UnitOfWork unitOfWork;
    private final Provider<TransactionContext> transaction;

    @Inject
    public RequestTransactions(UnitOfWork unitOfWork, Provider<TransactionContext> transaction) {
        this.unitOfWork = unitOfWork;
        this.transaction = transaction;
    }

    public Handler inTransaction(Handler handler) {
        return ctx -> unitOfWork.runInEventScope(() -> {
            var tx = transaction.get();
            try {
                handler.handle(ctx);
                tx.commit();
            } catch (RuntimeException failure) {
                rollback(tx, failure);
                throw failure;
            } catch (Exception failure) {
                rollback(tx, failure);
                throw new IllegalStateException("Request failed", failure);
            }
        });
    }

    private static void rollback(TransactionContext tx, Exception failure) {
        try {
            tx.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
