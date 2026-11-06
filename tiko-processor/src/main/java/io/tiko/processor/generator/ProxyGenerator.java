package io.tiko.processor.generator;

import com.palantir.javapoet.*;
import io.tiko.processor.core.GeneratorAnnotations;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.AbstractInterfaceMethods;
import io.tiko.processor.util.ProcessorContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Modifier;

/**
 * Generates proxy classes for cross-scope injection.
 *
 * When an EVENT-scoped bean is injected into a longer-lived (SINGLETON) scope,
 * we generate a proxy that implements the interface and delegates to the current scope instance.
 *
 * Example generated code:
 * <pre>
 * public final class RequestContextProxy implements RequestContext {
 *     private final TikoContainerImpl container;
 *
 *     public RequestContextProxy(TikoContainerImpl container) {
 *         this.container = container;
 *     }
 *
 *     @Override
 *     public String getRequestId() {
 *         return container.getCurrentRequestContext().getRequestId();
 *     }
 *
 *     @Override
 *     public String getUserId() {
 *         return container.getCurrentRequestContext().getUserId();
 *     }
 * }
 * </pre>
 */
public final class ProxyGenerator {

    private static final String GENERATED_PACKAGE = "io.tiko.generated";

    private final ProcessorContext context;

    public ProxyGenerator(ProcessorContext context) {
        this.context = context;
    }

    /**
     * Generates a proxy class for the given component if needed.
     * Returns true if proxy was generated, false if not needed.
     */
    public boolean generate(ComponentModel component) throws IOException {
        if (!component.requiresProxy()) {
            return false;
        }

        TypeRef interfaceType = component
                .getImplementedInterface()
                .orElseThrow(() -> new IllegalStateException("Proxy required but no interface found"));

        // Looked up by the rendered type: a parameterized interface (Repo<User>) finds no class,
        // so no proxy is generated for it.
        ClassDecl interfaceDecl =
                context.env().types().findClass(interfaceType.displayName()).orElse(null);
        if (interfaceDecl == null) {
            return false;
        }

        String proxyClassName = component.getClassName() + "Proxy";

        TypeSpec proxyClass = TypeSpec.classBuilder(proxyClassName)
                .addAnnotation(GeneratorAnnotations.generatedBy(ProxyGenerator.class))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(interfaceType.javaPoet())
                .addField(createContainerField())
                .addMethod(createConstructor())
                .addMethods(createDelegatingMethods(component, interfaceDecl))
                .build();

        JavaFile javaFile = JavaFile.builder(GENERATED_PACKAGE, proxyClass).build();

        context.env().output().writeJava(javaFile, List.of(component.getDecl()), false);
        return true;
    }

    /**
     * Generates a proxy class for a {@code @Produces} factory method whose output is an
     * interface in a shorter-lived scope than its consumers. The generated class is named
     * {@code <FactoryIdentifier>Proxy} (e.g., {@code JdbcConnectionProvider_connectionProxy})
     * and delegates each interface method to {@code container.produce_<id>().method(args)} —
     * which resolves to the current scope's value on every call.
     *
     * <p>Counterpart to {@link #generate(ComponentModel)} for the case where the
     * shorter-lived bean is sourced from a {@code @Produces} factory rather than a
     * {@code @Component} class.
     */
    public boolean generate(FactoryMethodModel factory) throws IOException {
        if (!factory.requiresProxy()) {
            return false;
        }

        TypeRef returnType = factory.getReturnType();
        ClassDecl interfaceDecl =
                context.env().types().findClass(factory.getReturnTypeName()).orElse(null);
        if (interfaceDecl == null) {
            return false;
        }

        String proxyClassName = factory.getFactoryIdentifier() + "Proxy";

        TypeSpec proxyClass = TypeSpec.classBuilder(proxyClassName)
                .addAnnotation(GeneratorAnnotations.generatedBy(ProxyGenerator.class))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(returnType.javaPoet())
                .addField(createContainerField())
                .addMethod(createConstructor())
                .addMethods(createFactoryDelegatingMethods(factory, interfaceDecl))
                .build();

        JavaFile javaFile = JavaFile.builder(GENERATED_PACKAGE, proxyClass).build();

        context.env().output().writeJava(javaFile, List.of(factory.getDeclaringClass()), false);
        return true;
    }

    /**
     * Creates the container field.
     */
    private FieldSpec createContainerField() {
        return FieldSpec.builder(
                        ClassName.get(GENERATED_PACKAGE, context.getContainerClassName()),
                        "container",
                        Modifier.PRIVATE,
                        Modifier.FINAL)
                .build();
    }

    /**
     * Creates the constructor.
     */
    private MethodSpec createConstructor() {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get(GENERATED_PACKAGE, context.getContainerClassName()), "container")
                .addStatement("this.container = container")
                .build();
    }

    /**
     * Creates delegating methods for all interface methods, including those inherited
     * from superinterfaces (e.g. {@code java.sql.Connection} inherits {@code unwrap}
     * and {@code isWrapperFor} from {@code java.sql.Wrapper}). Without this, the
     * generated proxy fails to compile against any interface that declares abstract
     * methods on a parent interface.
     */
    private List<MethodSpec> createDelegatingMethods(ComponentModel component, ClassDecl interfaceDecl) {
        List<MethodSpec> methods = new ArrayList<>();
        for (MethodDecl method : AbstractInterfaceMethods.collect(interfaceDecl)) {
            methods.add(createDelegatingMethod(method, generateGetActualInstanceCall(component)));
        }
        return methods;
    }

    /**
     * Creates a single delegating override that forwards the call to {@code delegateCall}'s
     * result: the current scope's instance for a component proxy, the factory output for a
     * factory proxy.
     */
    private MethodSpec createDelegatingMethod(MethodDecl method, String delegateCall) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(method.jvmName())
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(method.returnType().javaPoet());

        // Carry over type parameters (e.g. `<T> T unwrap(Class<T>)` from java.sql.Wrapper).
        methodBuilder.addTypeVariables(method.typeVariables());

        // Forward declared exceptions so the proxy preserves the interface's `throws` contract
        // (e.g. java.sql.Connection's checked SQLException).
        for (TypeRef thrown : method.thrownTypes()) {
            methodBuilder.addException(thrown.javaPoet());
        }

        List<String> paramNames = new ArrayList<>();
        method.parameters().forEach(param -> {
            String paramName = param.name();
            paramNames.add(paramName);
            methodBuilder.addParameter(param.type().javaPoet(), paramName);
        });

        String params = String.join(", ", paramNames);

        if (method.returnType().isVoid()) {
            methodBuilder.addStatement("$L.$L($L)", delegateCall, method.jvmName(), params);
        } else {
            methodBuilder.addStatement("return $L.$L($L)", delegateCall, method.jvmName(), params);
        }

        return methodBuilder.build();
    }

    /**
     * Creates delegating methods for a factory-output proxy. Each method body
     * resolves the current scope's value via {@code container.produce_<id>()} and
     * forwards the call.
     */
    private List<MethodSpec> createFactoryDelegatingMethods(FactoryMethodModel factory, ClassDecl interfaceDecl) {
        List<MethodSpec> methods = new ArrayList<>();
        String delegateCall = "container.produce_" + factory.getFactoryIdentifier() + "()";
        for (MethodDecl method : AbstractInterfaceMethods.collect(interfaceDecl)) {
            methods.add(createDelegatingMethod(method, delegateCall));
        }
        return methods;
    }

    /**
     * Generates the call to get the actual instance from the container.
     * e.g., "container.getCurrentEventContext()"
     */
    private String generateGetActualInstanceCall(ComponentModel component) {
        String methodName =
                switch (component.getScope()) {
                    case EVENT -> "getCurrent" + component.getClassName();
                    default ->
                        throw new IllegalStateException("Proxy only for EVENT scope, got: " + component.getScope());
                };

        return "container." + methodName + "()";
    }
}
