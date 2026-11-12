package io.tiko.examples.outbox.warehouse;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Produces;
import io.tiko.examples.outbox.Database;
import io.tiko.examples.outbox.OutboxRelay;
import javax.sql.DataSource;

@Component(scope = Scope.SINGLETON)
public class Resources {

    @Produces(scope = Scope.SINGLETON)
    public DataSource database(Settings settings) {
        return Database.open(settings.dbUrl());
    }

    @Produces(scope = Scope.SINGLETON)
    public OutboxRelay relay(DataSource database, Settings settings) {
        return new OutboxRelay(database, settings.kafkaBootstrap());
    }
}
