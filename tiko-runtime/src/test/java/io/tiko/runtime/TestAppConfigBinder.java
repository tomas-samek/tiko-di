package io.tiko.runtime;

import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.internal.coercers.Coercers;
import java.util.Map;
import java.util.Set;

/** An application's binder for tests (#531), next to a module's {@link TestModuleConfigBinder}. */
public final class TestAppConfigBinder implements ConfigBinder<TestAppConfigBinder.AppConfig> {

    public record AppConfig(String site) {}

    @Override
    public Class<AppConfig> type() {
        return AppConfig.class;
    }

    @Override
    public String prefix() {
        return "warehouse";
    }

    @Override
    public AppConfig bind(Map<String, Object> root, BindContext ctx) {
        Map<String, Object> node = ctx.requireSection(root, "warehouse");
        String site = ctx.scalarOrDefault(node, "site", "warehouse.site", Coercers.stringCoercer(), "main");
        ctx.checkUnknownKeys(node, "warehouse", Set.of("site"));
        return new AppConfig(site);
    }
}
