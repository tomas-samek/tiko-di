package io.tiko.runtime;

import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.internal.coercers.Coercers;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A module's config registry for tests (#114): one record claiming {@code tiko.kafka}, the way
 * tiko-kafka's registry does, so tests can put a module's {@code configs.txt} on a class loader.
 */
public final class TestModuleConfigRegistry {

    private TestModuleConfigRegistry() {}

    public record ModuleConfig(String servers) {}

    public static List<ConfigBinder<?>> all() {
        return List.of(new ConfigBinder<ModuleConfig>() {
            @Override
            public Class<ModuleConfig> type() {
                return ModuleConfig.class;
            }

            @Override
            public String prefix() {
                return "tiko.kafka";
            }

            @Override
            public ModuleConfig bind(Map<String, Object> root, BindContext ctx) {
                Map<String, Object> node = ctx.requireSection(root, "tiko.kafka");
                String servers = ctx.scalarOrDefault(
                        node, "servers", "tiko.kafka.servers", Coercers.stringCoercer(), "localhost");
                ctx.checkUnknownKeys(node, "tiko.kafka", Set.of("servers"));
                return new ModuleConfig(servers);
            }
        });
    }
}
