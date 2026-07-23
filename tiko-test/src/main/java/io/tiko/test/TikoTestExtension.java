package io.tiko.test;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.annotations.Named;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

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
        implements BeforeEachCallback,
                AfterEachCallback,
                BeforeAllCallback,
                AfterAllCallback,
                ParameterResolver,
                InvocationInterceptor {

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
        Class<?> type = pCtx.getParameter().getType();
        // Tiko always owns the handles it puts in the store.
        if (type == Container.class || type == EventBus.class || type == RecordingEventBus.class) {
            return true;
        }
        // Yield to JUnit's built-in resolvers — claiming a parameter they also support throws
        // "Discovered multiple competing ParameterResolvers" before the test body runs (#351).
        // Everything else is a container-wired dependency; container.get(...) inside
        // resolveParameter throws a clear error if the type is not actually wireable.
        return !isJUnitManaged(pCtx);
    }

    /**
     * True when {@code pCtx} is a parameter JUnit's own resolvers supply — by type
     * ({@link org.junit.jupiter.api.TestInfo}, {@link org.junit.jupiter.api.TestReporter},
     * {@link org.junit.jupiter.api.RepetitionInfo}) or by the {@link org.junit.jupiter.api.io.TempDir}
     * annotation. {@code @TikoTest} declines these so the built-in resolver wins (#351).
     */
    private static boolean isJUnitManaged(ParameterContext pCtx) {
        Class<?> type = pCtx.getParameter().getType();
        return type == org.junit.jupiter.api.TestInfo.class
                || type == org.junit.jupiter.api.TestReporter.class
                || type == org.junit.jupiter.api.RepetitionInfo.class
                || pCtx.isAnnotated(org.junit.jupiter.api.io.TempDir.class);
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

    @Override
    public void interceptTestMethod(
            Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext eCtx)
            throws Throwable {
        Container container = store(eCtx).get(KEY_CONTAINER, Container.class);
        Method method = invocationContext.getExecutable();
        boolean evt = method.isAnnotationPresent(EventScopeTest.class);

        if (!evt) {
            invocation.proceed();
            return;
        }

        // Capture the proceed() call so we can rethrow its Throwable unchanged after
        // unwinding the Runnable-based scope helpers (which can't propagate checked exceptions).
        Throwable[] thrown = new Throwable[1];
        Runnable body = () -> {
            try {
                invocation.proceed();
            } catch (Throwable t) {
                thrown[0] = t;
            }
        };

        container.runInEventScope(body);

        if (thrown[0] != null) {
            throw thrown[0];
        }
    }

    private void bootContainer(ExtensionContext ctx) {
        // Supply a CountingThreadPoolExecutor so awaitAsyncDispatch(Duration) detects drain
        // deterministically (#443). Because it is supplied, the container does not own it
        // (ownsEventExecutor = false) — closeContainer shuts it down.
        CountingThreadPoolExecutor executor = CountingThreadPoolExecutor.withFrameworkDefaults();
        TikoOptions opts = TikoOptions.builder()
                .eventBusDecorator(RecordingEventBus::new)
                .eventExecutor(executor)
                .build();
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
        if (container == null) return;
        // Capture the supplied executor before close(); the container will not shut it down for us.
        var executor = container.getEventExecutor();
        container.close();
        if (executor instanceof CountingThreadPoolExecutor cte) {
            cte.shutdownNow();
        }
    }

    private static Store store(ExtensionContext ctx) {
        return ctx.getStore(NS);
    }

    private static TikoTest.Lifecycle lifecycle(ExtensionContext ctx) {
        TikoTest ann = ctx.getRequiredTestClass().getAnnotation(TikoTest.class);
        return ann != null ? ann.lifecycle() : TikoTest.Lifecycle.PER_METHOD;
    }
}
