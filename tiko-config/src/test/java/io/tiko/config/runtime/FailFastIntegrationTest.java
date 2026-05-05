package io.tiko.config.runtime;

import io.tiko.Tiko;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailFastIntegrationTest {

    /**
     * If a META-INF/tiko/configs.txt exists on the classpath, the no-arg Tiko.create()
     * must throw with the prescribed message rather than constructing the container.
     */
    @Test
    void no_arg_create_throws_when_manifest_present(@TempDir Path tmp) throws IOException {
        Path metaDir = tmp.resolve("META-INF/tiko");
        Files.createDirectories(metaDir);
        Files.writeString(metaDir.resolve("configs.txt"), "io.example.FakeConfig=fake\n");

        ClassLoader synthetic = new java.net.URLClassLoader(new java.net.URL[] { tmp.toUri().toURL() },
            Thread.currentThread().getContextClassLoader());

        ClassLoader prior = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(synthetic);
        try {
            assertThatThrownBy(Tiko::create)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FakeConfig")
                .hasMessageContaining("ConfigSource");
        } finally {
            Thread.currentThread().setContextClassLoader(prior);
        }
    }
}
