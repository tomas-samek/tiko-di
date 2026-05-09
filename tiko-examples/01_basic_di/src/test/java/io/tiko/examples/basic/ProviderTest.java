package io.tiko.examples.basic;

import io.tiko.Container;
import io.tiko.Provider;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderTest {

    @Test
    void getProvider_returnsWorkingProvider() {
        try (Container container = Tiko.create()) {
            Provider<MessageService> provider = container.getProvider(MessageService.class);
            MessageService a = provider.get();
            MessageService b = provider.get();
            assertThat(a).isSameAs(b); // SINGLETON
        }
    }

    @Test
    void getProvider_isLazy_doesNotResolveEagerly() {
        try (Container container = Tiko.create()) {
            // Obtaining the Provider must not invoke get(type) yet —
            // an unknown type should only fail when .get() is called.
            Provider<String> provider = container.getProvider(String.class);
            assertThatThrownBy(provider::get)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void getProvider_withName_returnsNamedComponent() {
        try (Container container = Tiko.create()) {
            Provider<Greeter> english = container.getProvider(Greeter.class, "english");
            Provider<Greeter> spanish = container.getProvider(Greeter.class, "spanish");

            assertThat(english.get()).isInstanceOf(EnglishGreeter.class);
            assertThat(spanish.get()).isInstanceOf(SpanishGreeter.class);
            assertThat(english.get().greet()).isEqualTo("Hello");
        }
    }

    @Test
    void injectedProvider_yieldsFreshPrototypeInstanceEachCall() {
        try (Container container = Tiko.create()) {
            TicketBooth booth = container.get(TicketBooth.class);
            Ticket t1 = booth.issue();
            Ticket t2 = booth.issue();
            assertThat(t1.getId()).isNotEqualTo(t2.getId());
        }
    }

    @Test
    void getProvider_prototype_yieldsFreshInstanceEachCall() {
        try (Container container = Tiko.create()) {
            Provider<Ticket> provider = container.getProvider(Ticket.class);
            long id1 = provider.get().getId();
            long id2 = provider.get().getId();
            assertThat(id1).isNotEqualTo(id2);
        }
    }
}
