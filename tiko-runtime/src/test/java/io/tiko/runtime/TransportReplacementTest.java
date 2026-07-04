package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.ContainerInitializationException;
import io.tiko.TransportBootstrap;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransportReplacementTest {

    /** Stands in for a generated transport; the marker subtype is the substitution key. */
    static class StubTransport implements TransportBootstrap {
        @Override
        public void start(Container container) {}

        @Override
        public void shutdown() {}
    }

    /** A second, unrelated transport type to prove matching is selective. */
    static class OtherTransport implements TransportBootstrap {
        @Override
        public void start(Container container) {}

        @Override
        public void shutdown() {}
    }

    @Test
    void matchingTransportIsReplaced() {
        var discovered = new StubTransport();
        var replacement = new OtherTransport();
        var options = TikoOptions.builder()
                .replaceTransport(StubTransport.class, t -> replacement)
                .build();

        List<TransportBootstrap> result = Tiko.applyTransportReplacements(List.of(discovered), options);

        assertThat(result).containsExactly(replacement);
    }

    @Test
    void nullResultDropsTheTransport() {
        var options = TikoOptions.builder()
                .replaceTransport(StubTransport.class, t -> null)
                .build();

        List<TransportBootstrap> result =
                Tiko.applyTransportReplacements(List.of(new StubTransport(), new OtherTransport()), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(OtherTransport.class);
    }

    @Test
    void baseInterfaceKeyMatchesEveryTransport() {
        var options = TikoOptions.builder()
                .replaceTransport(TransportBootstrap.class, t -> null)
                .build();

        List<TransportBootstrap> result =
                Tiko.applyTransportReplacements(List.of(new StubTransport(), new OtherTransport()), options);

        assertThat(result).isEmpty();
    }

    @Test
    void unmatchedKeyFailsFastNamingDiscoveredTransports() {
        var options = TikoOptions.builder()
                .replaceTransport(OtherTransport.class, t -> t)
                .build();

        assertThatThrownBy(() -> Tiko.applyTransportReplacements(List.of(new StubTransport()), options))
                .isInstanceOf(ContainerInitializationException.class)
                .hasMessageContaining("OtherTransport")
                .hasMessageContaining("StubTransport")
                .hasMessageContaining("Suggested fixes");
    }

    @Test
    void unmatchedKeyWithNoTransportsAtAllStillFailsFast() {
        var options = TikoOptions.builder()
                .replaceTransport(StubTransport.class, t -> t)
                .build();

        assertThatThrownBy(() -> Tiko.applyTransportReplacements(List.of(), options))
                .isInstanceOf(ContainerInitializationException.class)
                .hasMessageContaining("(none)");
    }

    @Test
    void throwingDecoratorIsWrappedWithTheKeyName() {
        var options = TikoOptions.builder()
                .replaceTransport(StubTransport.class, t -> {
                    throw new IllegalStateException("boom");
                })
                .build();

        assertThatThrownBy(() -> Tiko.applyTransportReplacements(List.of(new StubTransport()), options))
                .isInstanceOf(ContainerInitializationException.class)
                .hasMessageContaining("StubTransport")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void noReplacementsReturnsTheDiscoveredListUnchanged() {
        var discovered = List.<TransportBootstrap>of(new StubTransport());

        assertThat(Tiko.applyTransportReplacements(
                        discovered, TikoOptions.builder().build()))
                .isSameAs(discovered);
    }

    @Test
    void duplicateKeyRegistrationThrowsAtBuilderTime() {
        var builder = TikoOptions.builder().replaceTransport(StubTransport.class, t -> t);

        assertThatThrownBy(() -> builder.replaceTransport(StubTransport.class, t -> null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("StubTransport");
    }
}
