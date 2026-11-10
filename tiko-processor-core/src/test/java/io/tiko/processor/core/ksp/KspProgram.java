package io.tiko.processor.core.ksp;

import io.tiko.processor.core.ksp.KspCompiler.KspResult;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs a program {@link io.tiko.processor.core.ksp.KspCompiler} compiled: boots
 * {@code Tiko.create()} over the compiled classes and generated resources, and reads the
 * program's state back.
 *
 * <p>The class loader is isolated (its parent is the platform loader) and holds the compiled
 * output plus the test's own classpath. The Tiko runtime is therefore loaded next to the
 * generated container, as in an application: it finds the container through
 * {@code META-INF/tiko} and loads it by name. The program's types are reached by reflection;
 * JDK types ({@code List}, {@code AtomicInteger}, {@code Runnable}) are shared with the test.
 */
public final class KspProgram implements AutoCloseable {

    private final URLClassLoader loader;
    private final Object container;
    private final Object broker;
    private final List<List<Thread>> leakReports;

    private KspProgram(URLClassLoader loader, Object container, Object broker, List<List<Thread>> leakReports) {
        this.loader = loader;
        this.container = container;
        this.broker = broker;
        this.leakReports = leakReports;
    }

    /** Boots {@code Tiko.create()} over the compiled program. */
    public static KspProgram boot(KspResult result) {
        return boot(result, null);
    }

    /**
     * Boots {@code Tiko.create(ConfigSources.file(yaml))} over the compiled program: the
     * {@code @Configuration} records bind from that YAML file.
     */
    public static KspProgram boot(KspResult result, Path yaml) {
        return bootWith(result, new Extras(), loader -> {
            var tiko = loader.loadClass("io.tiko.runtime.Tiko");
            if (yaml == null) {
                return tiko.getMethod("create").invoke(null);
            }
            var sourceType = loader.loadClass("io.tiko.ConfigSource");
            var source = loader.loadClass("io.tiko.config.ConfigSources")
                    .getMethod("file", Path.class)
                    .invoke(null, yaml);
            return tiko.getMethod("create", sourceType).invoke(null, source);
        });
    }

    /**
     * Boots {@code Tiko.create(TikoOptions.builder().testWiring(true).build())} over the compiled
     * program: the test container a test compile generated replaces the main one.
     */
    public static KspProgram bootTestWiring(KspResult result) {
        return bootWith(result, new Extras(), loader -> {
            var optionsType = loader.loadClass("io.tiko.runtime.TikoOptions");
            var builderFactory = optionsType.getMethod("builder");
            var builderType = builderFactory.getReturnType();
            var builder = builderFactory.invoke(null);
            builderType.getMethod("testWiring", boolean.class).invoke(builder, true);
            var options = builderType.getMethod("build").invoke(builder);
            return loader.loadClass("io.tiko.runtime.Tiko")
                    .getMethod("create", optionsType)
                    .invoke(null, options);
        });
    }

    /**
     * Boots with {@code testWiring(true)}, the Kafka transport replaced by a {@code FakeKafkaTransport}
     * over a fresh {@code FakeKafkaBroker}, and an {@code onThreadsOutlivingClose} handler that
     * records each report for {@link #leakReports()}. The records go in through
     * {@link #send(String, String, String)}.
     */
    public static KspProgram bootWithFakeKafka(KspResult result) {
        var extras = new Extras();
        return bootWith(result, extras, loader -> {
            var optionsType = loader.loadClass("io.tiko.runtime.TikoOptions");
            var builderFactory = optionsType.getMethod("builder");
            var builderType = builderFactory.getReturnType();
            var builder = builderFactory.invoke(null);
            builderType.getMethod("testWiring", boolean.class).invoke(builder, true);

            var brokerType = loader.loadClass("io.tiko.kafka.test.FakeKafkaBroker");
            var transportType = loader.loadClass("io.tiko.kafka.KafkaTransport");
            var broker = brokerType.getConstructor().newInstance();
            extras.broker = broker;
            var over = loader.loadClass("io.tiko.kafka.test.FakeKafkaTransport")
                    .getMethod("over", transportType, brokerType);
            Function<Object, Object> replacement = original -> invokeStatic(over, original, broker);
            builderType
                    .getMethod("replaceTransport", Class.class, Function.class)
                    .invoke(builder, transportType, replacement);

            Consumer<List<Thread>> record = threads -> extras.leakReports.add(List.copyOf(threads));
            builderType.getMethod("onThreadsOutlivingClose", Consumer.class).invoke(builder, record);

            var options = builderType.getMethod("build").invoke(builder);
            return loader.loadClass("io.tiko.runtime.Tiko")
                    .getMethod("create", optionsType)
                    .invoke(null, options);
        });
    }

    /**
     * Puts one record on the fake broker's {@code topic}. The fake stores a payload without a key,
     * so {@code key} only documents the record the test means to send.
     */
    public void send(String topic, String key, String json) {
        Objects.requireNonNull(broker, "booted without the fake Kafka broker");
        try {
            broker.getClass()
                    .getMethod("produce", String.class, byte[].class, String[].class)
                    .invoke(broker, topic, json.getBytes(StandardCharsets.UTF_8), new String[0]);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("broker.produce failed for key " + key, unwrap(e));
        }
    }

    /** The thread lists {@code onThreadsOutlivingClose} reported, one per report; empty after a clean close. */
    public List<List<Thread>> leakReports() {
        return List.copyOf(leakReports);
    }

    private static Object invokeStatic(Method method, Object... arguments) {
        try {
            return method.invoke(null, arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(method + " failed", unwrap(e));
        }
    }

    /** What a boot hands the program besides the container: the fake broker and the leak reports. */
    private static final class Extras {
        Object broker;
        final List<List<Thread>> leakReports = new CopyOnWriteArrayList<>();
    }

    /**
     * Boots with {@code create} over a fresh loader, which is closed again when the boot fails and
     * owned by the program otherwise.
     */
    private static KspProgram bootWith(KspResult result, Extras extras, ContainerFactory create) {
        var loader = new URLClassLoader(classpath(result.outputRoots()), ClassLoader.getPlatformClassLoader());
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try (var guard = new LoaderGuard(loader)) {
            var container = create.create(loader);
            return new KspProgram(guard.release(), container, extras.broker, extras.leakReports);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Tiko.create() failed", unwrap(e));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** {@code container.get(type)} for the program's type with this name. */
    public Object get(String typeName) {
        return invokeContainer("get", new Class<?>[] {Class.class}, load(typeName));
    }

    /** {@code container.runInEventScope(work)}: one unit of work. */
    public void runInEventScope(Runnable work) {
        invokeContainer("runInEventScope", new Class<?>[] {Runnable.class}, work);
    }

    /** The value of a public static field of the program's type with this name. */
    public Object staticField(String typeName, String field) {
        try {
            return load(typeName).getField(field).get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no static field " + typeName + "." + field, e);
        }
    }

    /** A public static {@code List} field of the program's type with this name. */
    @SuppressWarnings("unchecked")
    public List<Object> staticList(String typeName, String field) {
        return (List<Object>) staticField(typeName, field);
    }

    @Override
    public void close() throws Exception {
        try {
            ((AutoCloseable) container).close();
        } finally {
            loader.close();
        }
    }

    private Object invokeContainer(String method, Class<?>[] parameterTypes, Object... arguments) {
        try {
            return load("io.tiko.Container").getMethod(method, parameterTypes).invoke(container, arguments);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("container." + method + " failed", unwrap(e));
        }
    }

    private Class<?> load(String typeName) {
        try {
            return loader.loadClass(typeName);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("no class " + typeName, e);
        }
    }

    /** The output roots, then every entry of the test's classpath. */
    private static URL[] classpath(List<Path> outputRoots) {
        var urls = new ArrayList<URL>();
        try {
            for (var root : outputRoots) {
                urls.add(root.toUri().toURL());
            }
            for (var entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
        return urls.toArray(URL[]::new);
    }

    private static Throwable unwrap(ReflectiveOperationException e) {
        return e instanceof InvocationTargetException invocation ? invocation.getCause() : e;
    }

    /** Creates the container reflectively through the program's loader. */
    @FunctionalInterface
    private interface ContainerFactory {
        Object create(ClassLoader loader) throws ReflectiveOperationException;
    }

    /** Closes the loader on close unless it was released to the program. */
    private static final class LoaderGuard implements AutoCloseable {

        private final URLClassLoader loader;
        private boolean released;

        LoaderGuard(URLClassLoader loader) {
            this.loader = loader;
        }

        URLClassLoader release() {
            released = true;
            return loader;
        }

        @Override
        public void close() {
            if (!released) {
                closeLoader(loader);
            }
        }
    }

    private static void closeLoader(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
