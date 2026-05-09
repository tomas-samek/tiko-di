package io.tiko.examples.config;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;

public class Main {
    public static void main(String[] args) {
        try (Container container = Tiko.create(ConfigSources.classpath("config.yaml"))) {
            DataService service = container.get(DataService.class);
            System.out.println(service.describe());
        }
    }
}
