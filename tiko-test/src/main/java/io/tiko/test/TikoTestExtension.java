package io.tiko.test;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.annotations.Named;
import io.tiko.runtime.TikoOptions;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ReflectionSupport;

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
 *   <li>Shut the container down at the end of the matching lifecycle boundary, release its event
 *       executor on every path, and fail the test whose container left threads running (#637).</li>
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
    private static final String KEY_BOOT = "boot";

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
        BootedContainer boot = store(eCtx).get(KEY_BOOT, BootedContainer.class);
        if (boot == null) {
            throw new ParameterResolutionException("@TikoTest has no container to resolve parameter ["
                    + pCtx.getParameter() + "] in " + pCtx.getDeclaringExecutable() + ". With the default "
                    + "Lifecycle.PER_METHOD the container is booted for each test, so @BeforeAll and @AfterAll "
                    + "methods cannot take container parameters. Use @BeforeEach / @AfterEach, or "
                    + "@TikoTest(lifecycle = Lifecycle.PER_CLASS).");
        }
        Container container = boot.container();
        RecordingEventBus bus = boot.bus();

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
        Container container = store(eCtx).get(KEY_BOOT, BootedContainer.class).container();
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
        // Stored as a CloseableResource: JUnit closes it even if the matching after-callback never runs.
        store(ctx).put(KEY_BOOT, BootedContainer.boot(owner(ctx), testOptions(ctx)));
    }

    /**
     * Runs the {@link TikoTestOptions} methods of the test class: those of the classes enclosing a
     * {@code @Nested} class first, and within a class, those of its superclasses first.
     */
    private static Consumer<TikoOptions.Builder> testOptions(ExtensionContext ctx) {
        var methods = new ArrayList<Method>();
        for (var type : enclosingFirst(ctx.getRequiredTestClass())) {
            methods.addAll(AnnotationSupport.findAnnotatedMethods(
                    type, TikoTestOptions.class, HierarchyTraversalMode.TOP_DOWN));
        }
        var calls = new ArrayList<Consumer<TikoOptions.Builder>>();
        for (var method : methods) {
            checkSignature(method);
            var target = target(ctx, method);
            calls.add(builder -> ReflectionSupport.invokeMethod(method, target, builder));
        }
        return builder -> calls.forEach(call -> call.accept(builder));
    }

    /** The test class, preceded by the classes enclosing it when it is {@code @Nested}, outermost first. */
    private static List<Class<?>> enclosingFirst(Class<?> testClass) {
        var types = new ArrayDeque<Class<?>>();
        for (Class<?> type = testClass; type != null; type = type.getEnclosingClass()) {
            types.addFirst(type);
            if (!type.isAnnotationPresent(Nested.class)) {
                break;
            }
        }
        return List.copyOf(types);
    }

    private static void checkSignature(Method method) {
        if (method.getReturnType() != void.class
                || method.getParameterCount() != 1
                || method.getParameterTypes()[0] != TikoOptions.Builder.class) {
            throw new ExtensionConfigurationException("@TikoTestOptions method " + describe(method)
                    + " must return void and take one TikoOptions.Builder, for example:"
                    + " @TikoTestOptions void options(TikoOptions.Builder options) { ... }");
        }
    }

    /** {@code null} for a static method, else the test instance that declares it. */
    private static Object target(ExtensionContext ctx, Method method) {
        if (Modifier.isStatic(method.getModifiers())) {
            return null;
        }
        return ctx.getTestInstances()
                .flatMap(instances -> instances.findInstance(method.getDeclaringClass()))
                .orElseThrow(() -> new ExtensionConfigurationException("@TikoTestOptions method "
                        + describe(method) + " is an instance method, but this container boots before the"
                        + " test instance exists (Lifecycle.PER_CLASS). Make the method static, or annotate"
                        + " the class with @TestInstance(TestInstance.Lifecycle.PER_CLASS)."));
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    /**
     * Closes the boot stored for {@code ctx}. Throws an {@link AssertionError} when threads the
     * container or its event executor started are still running, which fails this test (or, per
     * class, this class) and no other (#637).
     */
    private void closeContainer(ExtensionContext ctx) {
        BootedContainer boot = store(ctx).remove(KEY_BOOT, BootedContainer.class);
        if (boot != null) {
            boot.close();
        }
    }

    /**
     * {@code Class.method} for a per-method container, {@code Class} for a per-class one. A
     * {@code @Nested} class is named with the classes enclosing it, {@code Outer.Nested}, so
     * same-named nested classes in different outer classes stay apart.
     */
    private static String owner(ExtensionContext ctx) {
        var className = className(ctx.getRequiredTestClass());
        return ctx.getTestMethod()
                .map(method -> className + "." + method.getName())
                .orElse(className);
    }

    private static String className(Class<?> testClass) {
        var enclosing = testClass.getEnclosingClass();
        if (enclosing != null && testClass.isAnnotationPresent(Nested.class)) {
            return className(enclosing) + "." + testClass.getSimpleName();
        }
        return testClass.getSimpleName();
    }

    private static Store store(ExtensionContext ctx) {
        return ctx.getStore(NS);
    }

    private static TikoTest.Lifecycle lifecycle(ExtensionContext ctx) {
        TikoTest ann = ctx.getRequiredTestClass().getAnnotation(TikoTest.class);
        return ann != null ? ann.lifecycle() : TikoTest.Lifecycle.PER_METHOD;
    }
}
