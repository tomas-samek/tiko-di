package io.tiko.examples.kafka.warehouse;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static void main(String[] args) throws Exception {
        TikoOptions opts = TikoOptions.builder()
                .configSource(ConfigSources.classpath("application.yaml"))
                .build();
        try (Container container = Tiko.create(opts)) {
            CountDownLatch stop = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown, "warehouse-shutdown"));
            System.out.println("warehouse-service ready, awaiting orders…");
            stop.await();
        }
    }
}
