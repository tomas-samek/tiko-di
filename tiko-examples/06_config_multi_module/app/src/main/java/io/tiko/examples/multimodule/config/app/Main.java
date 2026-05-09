package io.tiko.examples.multimodule.config.app;

import io.tiko.Container;
import io.tiko.Tiko;
import io.tiko.config.ConfigSources;
import io.tiko.examples.multimodule.config.core.CoreService;
import io.tiko.examples.multimodule.config.notifications.NotificationsService;

/**
 * Demonstrates layered multi-module configuration:
 * <ul>
 *   <li>The core module ships {@code core.retries=3}, {@code core.timeout=PT5S} as defaults.</li>
 *   <li>The notifications module ships {@code notifications.channel=email},
 *       {@code notifications.enabled=true} as defaults.</li>
 *   <li>{@code application.yaml} (this app's resource) overrides only {@code core.retries=10};
 *       every other value comes from the module-baked defaults.</li>
 * </ul>
 */
public class Main {

    public static void main(String[] args) {
        try (Container container = Tiko.create(ConfigSources.classpath("application.yaml"))) {
            CoreService core = container.get(CoreService.class);
            NotificationsService notifications = container.get(NotificationsService.class);

            System.out.println("=== Effective configuration ===");
            System.out.println("core.retries        = " + core.config().retries() + "  (override applied; default was 3)");
            System.out.println("core.timeout        = " + core.config().timeout() + "  (default from core jar)");
            System.out.println("notifications.channel = " + notifications.config().channel() + "  (default from notifications jar)");
            System.out.println("notifications.enabled = " + notifications.config().enabled() + "  (default from notifications jar)");
        }
    }
}
