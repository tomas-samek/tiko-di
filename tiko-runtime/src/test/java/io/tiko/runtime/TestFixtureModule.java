package io.tiko.runtime;

import io.tiko.TikoModule;

/** A second module, whose container is {@link FixtureTestContainer} (#537). */
public final class TestFixtureModule implements TikoModule {
    @Override
    public String descriptorRoot() {
        return "META-INF/tiko/modules/fixture/";
    }
}
