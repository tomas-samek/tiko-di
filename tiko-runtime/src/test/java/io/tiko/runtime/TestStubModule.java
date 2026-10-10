package io.tiko.runtime;

import io.tiko.TikoModule;

/** A module whose container is {@link StubContainer}, as generated code would describe it (#537). */
public final class TestStubModule implements TikoModule {
    @Override
    public String descriptorRoot() {
        return "META-INF/tiko/modules/stub/";
    }
}
