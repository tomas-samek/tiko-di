package io.tiko.runtime;

import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.internal.coercers.Coercers;
import java.util.Map;
import java.util.Set;

/** A module's binder for tests (#531): claims {@code tiko.kafka}, as tiko-kafka's binder does. */
public final class TestModuleConfigBinder implements ConfigBinder<TestModuleConfigBinder.ModuleConfig> {

    public record ModuleConfig(String servers) {}

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
        String servers =
                ctx.scalarOrDefault(node, "servers", "tiko.kafka.servers", Coercers.stringCoercer(), "localhost");
        ctx.checkUnknownKeys(node, "tiko.kafka", Set.of("servers"));
        return new ModuleConfig(servers);
    }
}
