package io.tiko.runtime;

import io.tiko.ConfigSource;
import io.tiko.ErrorHandler;
import io.tiko.config.ConfigBinder;
import io.tiko.config.ConfigSources;
import io.tiko.config.TikoFrameworkConfig;
import io.tiko.config.internal.TikoFrameworkConfigBinder;
import io.tiko.config.runtime.ConfigBootstrap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The tiko-config side of {@code Tiko.create} (#114). Loaded only when tiko-config is on the
 * classpath, so tiko-runtime keeps tiko-config optional.
 */
final class ConfigBinding {

    private ConfigBinding() {}

    /**
     * Binds the framework's own record plus every discovered one against the user source
     * layered over each module's {@code META-INF/tiko/defaults.yaml}, and splits the framework
     * record off. Throws
     * {@code ConfigValidationException}, after routing a {@code ConfigurationFailure} through
     * {@code errorHandler}, when binding fails.
     */
    static BoundConfigs bind(ConfigSource userSource, List<ConfigBinder<?>> discovered, ErrorHandler errorHandler) {
        ConfigSource defaults = ConfigSources.classpathAll("META-INF/tiko/defaults.yaml");
        ConfigSource effective = userSource == null ? defaults : ConfigSources.layered(defaults, userSource);
        var binders = new ArrayList<ConfigBinder<?>>();
        binders.add(new TikoFrameworkConfigBinder());
        binders.addAll(discovered);
        var bound = new LinkedHashMap<>(ConfigBootstrap.bind("config", effective, binders, errorHandler));
        var framework = (TikoFrameworkConfig) bound.remove(TikoFrameworkConfig.class);
        return new BoundConfigs(bound, framework == null ? null : framework.shutdownTimeout());
    }
}
