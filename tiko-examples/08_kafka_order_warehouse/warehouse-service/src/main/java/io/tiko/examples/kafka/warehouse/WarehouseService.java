package io.tiko.examples.kafka.warehouse;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.examples.kafka.events.OrderPlaced;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component(scope = Scope.SINGLETON)
public class WarehouseService {

    private final Path probeFile = Path.of(System.getProperty("probe.file", "/tmp/warehouse.probe"));

    @EventHandler
    public void on(OrderPlaced event) {
        System.out.println("warehouse received: " + event);
        try {
            Files.writeString(probeFile, event.orderId() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // probe file is optional — only used by the e2e test
        }
    }
}
