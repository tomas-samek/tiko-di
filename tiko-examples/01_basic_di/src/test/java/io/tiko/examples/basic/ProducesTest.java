package io.tiko.examples.basic;

import io.tiko.Container;
import io.tiko.Tiko;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProducesTest {

    @Test
    void instanceProduces_resolvedByNamedLookup() {
        try (Container container = Tiko.create()) {
            Cache memory = container.get(Cache.class, "memory");
            Cache distributed = container.get(Cache.class, "distributed");

            assertThat(memory.name()).isEqualTo("memory");
            assertThat(distributed.name()).isEqualTo("distributed");
        }
    }

    @Test
    void instanceProduces_singletonCachesTheProduct() {
        try (Container container = Tiko.create()) {
            Cache a = container.get(Cache.class, "memory");
            Cache b = container.get(Cache.class, "memory");
            assertThat(a).isSameAs(b);
        }
    }

    @Test
    void staticProduces_resolvedByType() {
        try (Container container = Tiko.create()) {
            Timestamp ts = container.get(Timestamp.class);
            assertThat(ts.getValue()).isGreaterThan(0);
        }
    }

    @Test
    void staticProduces_singletonCached() {
        try (Container container = Tiko.create()) {
            Timestamp a = container.get(Timestamp.class);
            Timestamp b = container.get(Timestamp.class);
            assertThat(a).isSameAs(b);
            assertThat(a.getValue()).isEqualTo(b.getValue());
        }
    }
}
