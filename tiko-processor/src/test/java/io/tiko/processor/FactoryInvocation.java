package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads, from a generated container's source, how it calls one {@code @Produces} method: the
 * statements of {@code invokeFactory_<id>()} up to its {@code catch}. The declaring component and
 * each argument are resolved in a statement of their own ahead of the {@code try} (#746), so a test
 * of what a parameter receives looks there, not at the call.
 */
final class FactoryInvocation {

    private FactoryInvocation() {}

    /**
     * @param container the generated container's source
     * @param factoryId the factory's identifier, {@code DeclaringClass_methodName}
     * @return the helper's statements on one line, single-spaced
     */
    static String of(String container, String factoryId) {
        String header = "invokeFactory_" + factoryId + "() {";
        int start = container.indexOf(header);
        assertThat(start).as("generated helper %s", header).isNotNegative();
        int end = container.indexOf(" catch (", start);
        assertThat(end).as("catch of %s", header).isGreaterThan(start);
        return container.substring(start, end).replaceAll("\\s+", " ");
    }
}
