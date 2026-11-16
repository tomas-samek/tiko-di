package io.tiko.processor.util;

import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.FactoryMethodModel;

/**
 * The names of the per-component classes a module's compile generates into
 * {@code io.tiko.generated}. Every module generates into that one package, so each name ends in
 * the module's hash, the one its container {@code TikoContainerImpl_<hash>} carries: two modules
 * that each have a component named {@code Health} get {@code HealthFactory_<hashA>} and
 * {@code HealthFactory_<hashB>}, not two classes with one name (#726).
 */
public final class GeneratedNames {

    private GeneratedNames() {}

    /** {@code <prefix><Component>Factory_<hash>}. */
    public static String factoryClass(ProcessorContext context, String prefix, ComponentModel component) {
        return prefix + component.getClassName() + "Factory" + moduleSuffix(context);
    }

    /** {@code <Component>Proxy_<hash>}. */
    public static String proxyClass(ProcessorContext context, ComponentModel component) {
        return component.getClassName() + "Proxy" + moduleSuffix(context);
    }

    /** {@code <DeclaringClass>_<method>Proxy_<hash>}, the proxy of a {@code @Produces} value. */
    public static String proxyClass(ProcessorContext context, FactoryMethodModel factory) {
        return factory.getFactoryIdentifier() + "Proxy" + moduleSuffix(context);
    }

    /** {@code EventRegistry_<hash>}, the event-handler wiring of the container this compile generates. */
    public static String eventRegistryClass(ProcessorContext context) {
        return "EventRegistry" + moduleSuffix(context);
    }

    /** {@code _<hash>}, taken from the name of the container this compile generates. */
    private static String moduleSuffix(ProcessorContext context) {
        var container = context.getContainerClassName();
        int underscore = container.lastIndexOf('_');
        return underscore < 0 ? "" : container.substring(underscore);
    }
}
