package io.tiko.examples.outbox.orders;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Produces;
import io.tiko.examples.outbox.Database;
import io.tiko.examples.outbox.OutboxRelay;
import java.sql.SQLException;
import javax.sql.DataSource;

@Component(scope = Scope.SINGLETON)
public class Resources {

    /** The database, with its schema: everything that takes it, the relay included, sees the tables. */
    @Produces(scope = Scope.SINGLETON)
    public DataSource database(Settings settings) throws SQLException {
        var database = Database.open(settings.dbUrl());
        OrderStore.createSchema(database);
        return database;
    }

    @Produces(scope = Scope.SINGLETON)
    public OutboxRelay relay(DataSource database, Settings settings) {
        return new OutboxRelay(database, settings.kafkaBootstrap());
    }
}
