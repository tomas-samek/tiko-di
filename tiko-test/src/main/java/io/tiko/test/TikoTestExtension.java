package io.tiko.test;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.annotations.Named;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit 5 extension wired up by {@link TikoTest}.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Boot a {@link Container} (with a {@link RecordingEventBus} decorator) at the chosen
 *       {@link TikoTest.Lifecycle} boundary and stash it in the extension store.</li>
 *   <li>Resolve test-method / lifecycle-method parameters from that container:
 *       {@link Container}, {@link EventBus}, and {@link RecordingEventBus} come from the
 *       store; every other type is resolved via {@code container.get(type[, name])}.</li>
 *   <li>Shut the container down at the end of the matching lifecycle boundary.</li>
 * </ul>
 */
public final class TikoTestExtension
        implements BeforeEachCallback, AfterEachCallback, BeforeAllCallback, AfterAllCallback, ParameterResolver {

    private static final Namespace NS = Namespace.create(TikoTestExtension.class);
    private static final String KEY_CONTAINER = "container";
    private static final String KEY_BUS = "bus";

    @Override
    public void beforeAll(ExtensionContext ctx) {
        if (lifecycle(ctx) == TikoTest.Lifecycle.PER_CLASS) {
            bootContainer(ctx);
        }
    }

    @Override
    public void afterAll(ExtensionContext ctx) {
        if (lifecycle(ctx) == TikoTest.Lifecycle.PER_CLASS) {
            closeContainer(ctx);
        }
    }

    @Override
    public void beforeEach(ExtensionContext ctx) {
        if (lifecycle(ctx) == TikoTest.Lifecycle.PER_METHOD) {
            bootContainer(ctx);
        }
    }

    @Override
    public void afterEach(ExtensionContext ctx) {
        if (lifecycle(ctx) == TikoTest.Lifecycle.PER_METHOD) {
            closeContainer(ctx);
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext pCtx, ExtensionContext eCtx) {
        // Resolve any parameter; container.get(...) inside resolveParameter will throw clearly
        // if the type is not wireable. Returning false here would let other extensions take
        // over, which would mask the more helpful container error.
        return true;
    }

    @Override
    public Object resolveParameter(ParameterContext pCtx, ExtensionContext eCtx) {
        Class<?> type = pCtx.getParameter().getType();
        Container container = store(eCtx).get(KEY_CONTAINER, Container.class);
        RecordingEventBus bus = store(eCtx).get(KEY_BUS, RecordingEventBus.class);

        if (type == Container.class) return container;
        if (type == EventBus.class) return bus;
        if (type == RecordingEventBus.class) return bus;

        Named named = pCtx.getParameter().getAnnotation(Named.class);
        if (named != null && !named.value().isEmpty()) {
            return container.get(type, named.value());
        }
        return container.get(type);
    }

    private void bootContainer(ExtensionContext ctx) {
        TikoOptions opts =
                TikoOptions.builder().eventBusDecorator(RecordingEventBus::new).build();
        Container container = Tiko.create(opts);
        RecordingEventBus bus = (RecordingEventBus) container.getEventBus();
        // Wire the executor in so RecordingEventBus.awaitAsyncDispatch(Duration) works.
        bus.setEventExecutor(container.getEventExecutor());
        store(ctx).put(KEY_CONTAINER, container);
        store(ctx).put(KEY_BUS, bus);
    }

    private void closeContainer(ExtensionContext ctx) {
        Container container = store(ctx).remove(KEY_CONTAINER, Container.class);
        store(ctx).remove(KEY_BUS);
        if (container != null) container.close();
    }

    private static Store store(ExtensionContext ctx) {
        return ctx.getStore(NS);
    }

    private static TikoTest.Lifecycle lifecycle(ExtensionContext ctx) {
        TikoTest ann = ctx.getRequiredTestClass().getAnnotation(TikoTest.class);
        return ann != null ? ann.lifecycle() : TikoTest.Lifecycle.PER_METHOD;
    }
}
