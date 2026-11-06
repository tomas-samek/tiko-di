package io.tiko.processor.ksp;

import io.tiko.processor.core.ksp.KspCompiler.KspResult;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
final class KspProgram implements AutoCloseable {

    private final URLClassLoader loader;
    private final Object container;

    private KspProgram(URLClassLoader loader, Object container) {
        this.loader = loader;
        this.container = container;
    }

    /** Boots {@code Tiko.create()} over the compiled program. */
    static KspProgram boot(KspResult result) {
        return boot(result, null);
    }

    /**
     * Boots {@code Tiko.create(ConfigSources.file(yaml))} over the compiled program: the
     * {@code @Configuration} records bind from that YAML file.
     */
    static KspProgram boot(KspResult result, Path yaml) {
        return boot(new URLClassLoader(classpath(result.outputRoots()), ClassLoader.getPlatformClassLoader()), yaml);
    }

    /** Boots over {@code loader}, which is closed again when the boot fails and owned by the program otherwise. */
    private static KspProgram boot(URLClassLoader loader, Path yaml) {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try (var guard = new LoaderGuard(loader)) {
            var tiko = loader.loadClass("io.tiko.runtime.Tiko");
            Object container;
            if (yaml == null) {
                container = tiko.getMethod("create").invoke(null);
            } else {
                var sourceType = loader.loadClass("io.tiko.ConfigSource");
                var source = loader.loadClass("io.tiko.config.ConfigSources")
                        .getMethod("file", Path.class)
                        .invoke(null, yaml);
                container = tiko.getMethod("create", sourceType).invoke(null, source);
            }
            return new KspProgram(guard.release(), container);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Tiko.create() failed", unwrap(e));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** {@code container.get(type)} for the program's type with this name. */
    Object get(String typeName) {
        return invokeContainer("get", new Class<?>[] {Class.class}, load(typeName));
    }

    /** {@code container.runInEventScope(work)}: one unit of work. */
    void runInEventScope(Runnable work) {
        invokeContainer("runInEventScope", new Class<?>[] {Runnable.class}, work);
    }

    /** The value of a public static field of the program's type with this name. */
    Object staticField(String typeName, String field) {
        try {
            return load(typeName).getField(field).get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no static field " + typeName + "." + field, e);
        }
    }

    /** A public static {@code List} field of the program's type with this name. */
    @SuppressWarnings("unchecked")
    List<Object> staticList(String typeName, String field) {
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
