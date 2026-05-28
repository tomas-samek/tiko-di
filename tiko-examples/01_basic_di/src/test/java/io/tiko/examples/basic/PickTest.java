package io.tiko.examples.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.NoSuchComponentException;
import io.tiko.Pick;
import io.tiko.Provider;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.Test;

class PickTest {

    @Test
    void resolve_withoutFilters_isEquivalentToGet() {
        try (Container container = Tiko.create()) {
            MessageService viaPick = container.pick(MessageService.class).resolve();
            MessageService viaGet = container.get(MessageService.class);
            assertThat(viaPick).isSameAs(viaGet);
        }
    }

    @Test
    void resolve_withName_isEquivalentToGetByName() {
        try (Container container = Tiko.create()) {
            Greeter english = container.pick(Greeter.class).withName("english").resolve();
            assertThat(english).isInstanceOf(EnglishGreeter.class);
            assertThat(english).isSameAs(container.get(Greeter.class, "english"));
        }
    }

    @Test
    void asProvider_lazilyResolvesEachCall() {
        try (Container container = Tiko.create()) {
            Provider<Greeter> provider =
                    container.pick(Greeter.class).withName("spanish").asProvider();
            Greeter first = provider.get();
            Greeter second = provider.get();
            assertThat(first).isInstanceOf(SpanishGreeter.class);
            assertThat(first).isSameAs(second);
        }
    }

    @Test
    void orDefault_returnsResolvedBeanWhenPresent() {
        try (Container container = Tiko.create()) {
            Greeter fallback = () -> "Bonjour";
            Greeter english = container.pick(Greeter.class).withName("english").orDefault(fallback);
            assertThat(english).isInstanceOf(EnglishGreeter.class);
            assertThat(english).isNotSameAs(fallback);
        }
    }

    @Test
    void orDefault_returnsFallbackWhenNameUnknown() {
        try (Container container = Tiko.create()) {
            Greeter fallback = () -> "Bonjour";
            Greeter french = container.pick(Greeter.class).withName("french").orDefault(fallback);
            assertThat(french).isSameAs(fallback);
            assertThat(french.greet()).isEqualTo("Bonjour");
        }
    }

    @Test
    void resolve_unknownName_throws() {
        try (Container container = Tiko.create()) {
            Pick<Greeter> pick = container.pick(Greeter.class).withName("french");
            assertThatThrownBy(pick::resolve).isInstanceOf(NoSuchComponentException.class);
        }
    }

    @Test
    void asProvider_unknownName_throwsOnGet() {
        try (Container container = Tiko.create()) {
            Provider<Greeter> provider =
                    container.pick(Greeter.class).withName("french").asProvider();
            assertThatThrownBy(provider::get).isInstanceOf(NoSuchComponentException.class);
        }
    }
}
