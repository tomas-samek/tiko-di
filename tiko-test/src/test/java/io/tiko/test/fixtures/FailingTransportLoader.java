package io.tiko.test.fixtures;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Puts {@link FailingTransport} on the context class loader the extension boots with, and takes it
 * off again. Install it in a fixture's {@code @BeforeAll}, which runs before the extension boots.
 */
public final class FailingTransportLoader {
    private static ClassLoader previous;
    private static URLClassLoader withFailingTransport;

    private FailingTransportLoader() {}

    public static void install() {
        URL root = FailingTransportLoader.class.getResource("/boot-failure/");
        previous = Thread.currentThread().getContextClassLoader();
        withFailingTransport = new URLClassLoader(new URL[] {root}, previous);
        Thread.currentThread().setContextClassLoader(withFailingTransport);
    }

    public static void restore() throws IOException {
        Thread.currentThread().setContextClassLoader(previous);
        withFailingTransport.close();
    }
}
