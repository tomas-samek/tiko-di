package io.tiko.processor.collect;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Produces;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeRefs;
import io.tiko.processor.util.TypeUtil;
import java.util.Objects;

/**
 * Builds a {@link FactoryMethodModel} for every {@code @Produces} method of the round and
 * registers it with the {@link ProcessorContext}. A component's self-{@code @Produces} static
 * factory is skipped: {@link ComponentCollector} absorbs it as the component's instantiation
 * strategy.
 */
public final class FactoryMethodCollector {

    private static final String COMPONENT = Component.class.getCanonicalName();
    private static final String PRODUCES = Produces.class.getCanonicalName();

    private FactoryMethodCollector() {}

    /** Collects the round's {@code @Produces} methods into {@code ctx}. */
    public static void collect(ProcessingEnv env, ProcessorContext ctx) {
        var typeUtil = new TypeUtil(env.types());
        for (var method : env.methodsAnnotatedWith(PRODUCES)) {
            if (ctx.isRejected(method)) {
                continue; // a Kotlin construct DI does not support, already reported
            }
            // Skip a self-@Produces method already absorbed by its @Component model
            // (static method on the component class returning that class with matching qualifier).
            if (isSelfStaticFactory(method, typeUtil)) {
                continue;
            }

            var factory = buildFactoryMethodModel(method, typeUtil, ctx);
            if (factory != null) {
                ctx.registerFactoryMethod(factory);
            }
        }
    }

    /**
     * Whether {@code method} is a {@code @Component}'s self-{@code @Produces} factory: static,
     * declared on the component, returning the component type under the component's own name.
     */
    private static boolean isSelfStaticFactory(MethodDecl method, TypeUtil typeUtil) {
        if (!method.isStatic()) return false;
        var declaringType = method.owner().orElse(null);
        if (declaringType == null) return false;
        var componentAnnotation = declaringType.annotation(COMPONENT).orElse(null);
        if (componentAnnotation == null) return false;
        if (!typeUtil.isSameType(method.returnType(), declaringType.asType())) return false;
        var produces = method.annotation(PRODUCES);
        return produces.isPresent()
                && Objects.equals(produces.get().string("name"), componentAnnotation.string("name"));
    }

    /** Builds a FactoryMethodModel from a @Produces method; {@code null} when an error was reported. */
    private static FactoryMethodModel buildFactoryMethodModel(
            MethodDecl method, TypeUtil typeUtil, ProcessorContext ctx) {
        var annotation = method.annotation(PRODUCES).orElseThrow();
        var declaringClass = method.owner().orElseThrow();

        var returnType = method.returnType();
        var returnTypeName = typeUtil.getQualifiedName(returnType);

        // Build dependencies from method parameters
        var dependencies = DependencyResolver.resolve(method, typeUtil, ctx.getErrorReporter());
        if (dependencies.isEmpty()) {
            return null; // an unresolved @Pick was reported
        }

        // Factory-produced beans implementing AutoCloseable get implicit close() at teardown,
        // covering the common case of @Produces returning third-party types (HikariDataSource,
        // HttpClient, KafkaProducer, etc.).
        boolean factoryAutoCloseable = typeUtil.isAssignableTo(returnType, "java.lang.AutoCloseable");

        // Auto-proxy: when an EVENT-scoped factory produces an interface, a longer-lived
        // consumer (SINGLETON injecting an EVENT-scoped Connection, for example) needs a proxy
        // that resolves to the current scope's value on every method call — same teaching as
        // @Component-based cross-scope proxies. Proxying a concrete class would require bytecode
        // tricks Tiko deliberately avoids, so we only emit a proxy for interface returns.
        var scope = Scope.valueOf(annotation.enumName("scope"));
        boolean requiresProxy = scope == Scope.EVENT
                && TypeRefs.declared(returnType)
                        .filter(decl -> decl.kind() == DeclKind.INTERFACE)
                        .isPresent();

        return FactoryMethodModel.builder()
                .method(method)
                .declaringClass(declaringClass)
                .methodName(method.name())
                .returnType(returnType)
                .returnTypeName(returnTypeName)
                .scope(scope)
                .name(annotation.string("name"))
                .profiles(annotation.strings("profiles"))
                .dependencies(dependencies.get())
                .autoCloseable(factoryAutoCloseable)
                .requiresProxy(requiresProxy)
                .build();
    }
}
