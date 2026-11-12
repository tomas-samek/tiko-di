package io.tiko.examples.outbox.warehouse;

import io.tiko.config.ConfigSources;
import io.tiko.examples.outbox.OutboxRelay;
import io.tiko.runtime.Tiko;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        var daemon = Tiko.daemon(ConfigSources.classpath("warehouse.yaml"));
        try {
            daemon.container().get(OutboxRelay.class);
            System.out.println("outbox-warehouse ready");
            daemon.awaitShutdown();
        } finally {
            daemon.stop();
        }
    }
}
