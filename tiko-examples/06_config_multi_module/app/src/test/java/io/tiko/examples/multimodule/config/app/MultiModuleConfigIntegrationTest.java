package io.tiko.examples.multimodule.config.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.multimodule.config.core.CoreService;
import io.tiko.examples.multimodule.config.notifications.NotificationsService;
import io.tiko.runtime.Tiko;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage for #18: cross-module configuration aggregation with
 * module-baked defaults and a single optional external override.
 *
 * <p>The classpath here contains two jars (core and notifications), each shipping
 * its own {@code META-INF/tiko/defaults.yaml}. Tiko discovers and layers them.</p>
 */
class MultiModuleConfigIntegrationTest {

    @Test
    void defaults_only_no_user_source_works_when_every_field_is_defaulted() {
        try (Container container = Tiko.create()) {
            CoreService core = container.get(CoreService.class);
            NotificationsService notifications = container.get(NotificationsService.class);

            assertThat(core.config().retries()).isEqualTo(3);
            assertThat(core.config().timeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(notifications.config().channel()).isEqualTo("email");
            assertThat(notifications.config().enabled()).isTrue();
        }
    }

    @Test
    void user_override_wins_per_key_other_values_keep_module_defaults() {
        try (Container container = Tiko.create(ConfigSources.classpath("application.yaml"))) {
            CoreService core = container.get(CoreService.class);
            NotificationsService notifications = container.get(NotificationsService.class);

            // application.yaml sets core.retries=10; everything else stays on defaults.
            assertThat(core.config().retries()).isEqualTo(10);
            assertThat(core.config().timeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(notifications.config().channel()).isEqualTo("email");
            assertThat(notifications.config().enabled()).isTrue();
        }
    }

    @Test
    void in_memory_override_works_the_same_as_a_file_override() {
        try (Container container =
                Tiko.create(ConfigSources.fromMap(Map.of("notifications", Map.of("channel", "sms"))))) {
            NotificationsService notifications = container.get(NotificationsService.class);
            CoreService core = container.get(CoreService.class);

            // notifications.channel overridden; everything else still from defaults.
            assertThat(notifications.config().channel()).isEqualTo("sms");
            assertThat(notifications.config().enabled()).isTrue();
            assertThat(core.config().retries()).isEqualTo(3);
        }
    }

    @Test
    void config_records_route_to_the_right_module_container() {
        try (Container container = Tiko.create()) {
            // get(Class) on the AggregatingContainer must find each record in the module
            // that declared it — different modules, different per-module containers.
            CoreService core = container.get(CoreService.class);
            NotificationsService notifications = container.get(NotificationsService.class);

            assertThat(core).isNotNull();
            assertThat(notifications).isNotNull();
        }
    }
}
