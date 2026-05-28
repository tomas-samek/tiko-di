package io.tiko;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the public contract of the framework-originated exception hierarchy (#98): structured
 * fields, message shape, and the sealed root that lets callers catch any framework failure at once.
 */
class FrameworkExceptionsTest {

    @Test
    void noSuchComponentByTypeCarriesTypeAndNullQualifier() {
        NoSuchComponentException ex = new NoSuchComponentException(String.class);
        assertThat(ex.type()).isEqualTo(String.class);
        assertThat(ex.qualifier()).isNull();
        assertThat(ex.getMessage()).contains("No component found for type:").contains("java.lang.String");
    }

    @Test
    void noSuchComponentByTypeAndNameCarriesBothFields() {
        NoSuchComponentException ex = new NoSuchComponentException(String.class, "primary");
        assertThat(ex.type()).isEqualTo(String.class);
        assertThat(ex.qualifier()).isEqualTo("primary");
        assertThat(ex.getMessage()).contains("java.lang.String").contains("with name: primary");
    }

    @Test
    void containerShutDownHasCanonicalMessage() {
        assertThat(new ContainerShutDownException().getMessage()).isEqualTo("Container has been shut down");
    }

    @Test
    void containerInitializationPreservesCause() {
        Throwable cause = new IllegalStateException("boom");
        ContainerInitializationException ex = new ContainerInitializationException("init failed", cause);
        assertThat(ex.getMessage()).isEqualTo("init failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void everySubtypeIsCatchableAsTikoExceptionAndRuntimeException() {
        // The sealed root lets a caller handle any framework-originated failure uniformly, while the
        // RuntimeException base keeps it unchecked.
        TikoException[] all = {
            new NoSuchComponentException(String.class),
            new ContainerShutDownException(),
            new ContainerInitializationException("x"),
        };
        for (TikoException e : all) {
            assertThat(e).isInstanceOf(TikoException.class).isInstanceOf(RuntimeException.class);
        }
    }
}
