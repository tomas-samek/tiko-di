package io.tiko.examples.basic.expose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code @Component(expose = {…}, exposeSelf = …)} routing contract:
 *
 * <ul>
 *   <li><b>Permissive default exposes every implemented interface.</b> Two interfaces
 *       on the same impl both route, and both route to the same scope-cached instance.</li>
 *   <li><b>Explicit expose narrows the routable set.</b> A class implementing two
 *       interfaces but exposing only one rejects lookups for the hidden interface.</li>
 *   <li><b>{@code exposeSelf = false} hides the impl class</b> from
 *       {@code container.get(MyImpl.class)}.</li>
 *   <li><b>{@code AutoCloseable} is still recognised for framework dispatch</b> (implicit
 *       {@code close()} at scope exit) even when not listed in {@code expose}.</li>
 * </ul>
 */
class ExposureRoutingTest {

    @Test
    void permissiveDefaultExposesEveryImplementedInterface() {
        try (Container container = Tiko.create()) {
            Alpha viaAlpha = container.get(Alpha.class);
            Beta viaBeta = container.get(Beta.class);
            assertThat(viaAlpha).isInstanceOf(MultiInterfaceBean.class);
            assertThat(viaBeta).isInstanceOf(MultiInterfaceBean.class);
        }
    }

    @Test
    void twoInterfacesOnSameSingletonImplYieldSameInstance() {
        try (Container container = Tiko.create()) {
            Alpha viaAlpha = container.get(Alpha.class);
            Beta viaBeta = container.get(Beta.class);
            MultiInterfaceBean viaConcrete = container.get(MultiInterfaceBean.class);
            assertThat(viaAlpha).isSameAs(viaBeta);
            assertThat(viaAlpha).isSameAs(viaConcrete);
        }
    }

    @Test
    void explicitExposeNarrowsTheRoutableSet() {
        try (Container container = Tiko.create()) {
            // Gamma is in expose — routable.
            Gamma viaGamma = container.get(Gamma.class);
            assertThat(viaGamma).isInstanceOf(RestrictedBean.class);
            // Delta is implemented but NOT exposed — must not be routable.
            assertThatThrownBy(() -> container.get(Delta.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(Delta.class.getName());
            // exposeSelf is true by default — the concrete class still routes.
            RestrictedBean viaConcrete = container.get(RestrictedBean.class);
            assertThat(viaConcrete).isSameAs(viaGamma);
        }
    }

    @Test
    void exposeSelfFalseHidesTheConcreteClass() {
        try (Container container = Tiko.create()) {
            // Epsilon (in expose) is routable.
            Epsilon viaEpsilon = container.get(Epsilon.class);
            assertThat(viaEpsilon).isInstanceOf(SelfHiddenBean.class);
            // The concrete class is hidden — exposeSelf = false.
            assertThatThrownBy(() -> container.get(SelfHiddenBean.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(SelfHiddenBean.class.getName());
        }
    }

    @Test
    void autoCloseableStillRecognisedForTeardownEvenWhenNotExposed() {
        RestrictedCloseable.CLOSED.set(false);
        Container container = Tiko.create();
        Zeta z = container.get(Zeta.class);
        assertThat(z).isInstanceOf(RestrictedCloseable.class);
        container.shutdown();
        // The framework called close() at shutdown despite AutoCloseable not being in the
        // expose list — framework dispatch is orthogonal to injection routing.
        assertThat(RestrictedCloseable.CLOSED).isTrue();
    }
}
