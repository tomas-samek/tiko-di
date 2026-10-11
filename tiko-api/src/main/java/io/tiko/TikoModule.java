package io.tiko;

/**
 * One Tiko module on the classpath (#537). {@code tiko-processor} generates an implementation per
 * module and lists it in {@code META-INF/services/io.tiko.TikoModule}; {@code Tiko.create}
 * finds every module through {@link java.util.ServiceLoader}. A shaded fat jar merges those
 * services files, while it keeps only one copy of a fixed-name resource, so each module's
 * descriptor lives under a path of its own (ARCH-16).
 *
 * <p>Generated code implements this; applications don't.
 */
public interface TikoModule {

    /**
     * The classpath directory holding this module's {@code container.properties},
     * {@code components.txt} and {@code configs.txt}, ending in {@code /} — e.g.
     * {@code META-INF/tiko/modules/TikoContainerImpl_33c9eab1/}.
     */
    String descriptorRoot();
}
