package io.tiko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link DefaultPick} fluent surface: named axis, eager resolve, lazy Provider,
 * fallback on missing bean, and confirms immutability via reused-instance per-axis pattern.
 */
class DefaultPickTest {

    @Test
    void resolveWithoutNameDelegatesToGetByType() {
        ContainerStub container = new ContainerStub();
        Pick<String> pick = new DefaultPick<>(container, String.class);

        assertThat(pick.resolve()).isEqualTo("by-type");
        assertThat(container.getByTypeCalls).hasValue(1);
        assertThat(container.getByNameCalls).hasValue(0);
    }

    @Test
    void resolveWithNameDelegatesToGetByTypeAndName() {
        ContainerStub container = new ContainerStub();
        Pick<String> named = new DefaultPick<>(container, String.class).withName("primary");

        assertThat(named.resolve()).isEqualTo("by-name:primary");
        assertThat(container.getByNameCalls).hasValue(1);
        assertThat(container.getByTypeCalls).hasValue(0);
    }

    @Test
    void withNameReturnsNewInstanceAndDoesNotMutateOriginal() {
        ContainerStub container = new ContainerStub();
        Pick<String> original = new DefaultPick<>(container, String.class);
        Pick<String> named = original.withName("alt");

        assertThat(named).isNotSameAs(original);
        assertThat(original.resolve()).isEqualTo("by-type");
        assertThat(named.resolve()).isEqualTo("by-name:alt");
    }

    @Test
    void asProviderDefersResolutionAndReResolvesEachGet() {
        ContainerStub container = new ContainerStub();
        Provider<String> provider = new DefaultPick<>(container, String.class).asProvider();

        assertThat(container.getByTypeCalls).hasValue(0);
        provider.get();
        provider.get();
        assertThat(container.getByTypeCalls).hasValue(2);
    }

    @Test
    void orDefaultReturnsResolvedWhenPresent() {
        ContainerStub container = new ContainerStub();
        Pick<String> pick = new DefaultPick<>(container, String.class);

        assertThat(pick.orDefault("fallback")).isEqualTo("by-type");
    }

    @Test
    void orDefaultReturnsFallbackWhenNoSuchComponent() {
        ContainerStub container = new ContainerStub().throwingOnGet();
        Pick<String> pick = new DefaultPick<>(container, String.class);

        assertThat(pick.orDefault("fallback")).isEqualTo("fallback");
    }

    @Test
    void orDefaultPropagatesOtherExceptions() {
        ContainerStub container = new ContainerStub().throwingOnGetWith(new IllegalStateException("boom"));
        Pick<String> pick = new DefaultPick<>(container, String.class);

        assertThatThrownBy(() -> pick.orDefault("fallback"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    private static final class ContainerStub implements Container {
        final AtomicInteger getByTypeCalls = new AtomicInteger();
        final AtomicInteger getByNameCalls = new AtomicInteger();
        private RuntimeException onGet;

        ContainerStub throwingOnGet() {
            this.onGet = new NoSuchComponentException(String.class);
            return this;
        }

        ContainerStub throwingOnGetWith(RuntimeException ex) {
            this.onGet = ex;
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <X> X get(Class<X> type) {
            getByTypeCalls.incrementAndGet();
            if (onGet != null) throw onGet;
            return (X) "by-type";
        }

        @Override
        @SuppressWarnings("unchecked")
        public <X> X get(Class<X> type, String name) {
            getByNameCalls.incrementAndGet();
            if (onGet != null) throw onGet;
            return (X) ("by-name:" + name);
        }

        @Override
        public <X> java.util.List<X> getAll(Class<X> type) {
            return java.util.List.of();
        }

        @Override
        public <X> Provider<X> getProvider(Class<X> type) {
            return () -> get(type);
        }

        @Override
        public <X> Provider<X> getProvider(Class<X> type, String name) {
            return () -> get(type, name);
        }

        @Override
        public void runInEventScope(Runnable runnable) {
            runnable.run();
        }

        @Override
        public <X> X supplyInEventScope(java.util.function.Supplier<X> supplier) {
            return supplier.get();
        }

        @Override
        public void start() {
            // no-op: test stub
        }

        @Override
        public void shutdown() {
            // no-op: test stub
        }

        @Override
        public EventBus getEventBus() {
            throw new UnsupportedOperationException("not used in DefaultPick tests");
        }

        @Override
        public java.util.concurrent.ExecutorService getEventExecutor() {
            throw new UnsupportedOperationException("not used in DefaultPick tests");
        }
    }
}
