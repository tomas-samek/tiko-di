package io.tiko.config.internal;

import io.tiko.config.ConfigBinder;
import java.util.List;

/**
 * Hand-maintained registry advertising {@link TikoFrameworkConfigBinder}. Referenced from
 * tiko-config's {@code META-INF/tiko/configs.txt} so {@code Tiko.create()} binds
 * {@link io.tiko.config.TikoFrameworkConfig} whenever tiko-config is on the classpath.
 */
public final class TikoFrameworkConfigRegistry {

    private TikoFrameworkConfigRegistry() {}

    public static List<ConfigBinder<?>> all() {
        return List.of(new TikoFrameworkConfigBinder());
    }
}
