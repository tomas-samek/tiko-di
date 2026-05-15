package io.tiko.processor.generator;

import com.palantir.javapoet.*;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.GeneratorAnnotations;
import io.tiko.processor.util.ProcessorContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Generates proxy classes for cross-scope injection.
 *
 * When a shorter-lived scoped bean (REQUEST, EVENT) is injected into a longer-lived scope,
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

        TypeMirror interfaceType = component
                .getImplementedInterface()
                .orElseThrow(() -> new IllegalStateException("Proxy required but no interface found"));

        TypeElement interfaceElement = context.getElementUtils().getTypeElement(interfaceType.toString());
        if (interfaceElement == null) {
            return false;
        }

        String proxyClassName = component.getClassName() + "Proxy";

        TypeSpec proxyClass = TypeSpec.classBuilder(proxyClassName)
                .addAnnotation(GeneratorAnnotations.generatedBy(ProxyGenerator.class))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(TypeName.get(interfaceType))
                .addField(createContainerField())
                .addMethod(createConstructor())
                .addMethods(createDelegatingMethods(component, interfaceElement))
                .build();

        JavaFile javaFile = JavaFile.builder(GENERATED_PACKAGE, proxyClass).build();

        javaFile.writeTo(context.getFiler());
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

        TypeMirror returnType = factory.getReturnType();
        TypeElement interfaceElement = context.getElementUtils().getTypeElement(factory.getReturnTypeName());
        if (interfaceElement == null) {
            return false;
        }

        String proxyClassName = factory.getFactoryIdentifier() + "Proxy";

        TypeSpec proxyClass = TypeSpec.classBuilder(proxyClassName)
                .addAnnotation(GeneratorAnnotations.generatedBy(ProxyGenerator.class))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(TypeName.get(returnType))
                .addField(createContainerField())
                .addMethod(createConstructor())
                .addMethods(createFactoryDelegatingMethods(factory, interfaceElement))
                .build();

        JavaFile javaFile = JavaFile.builder(GENERATED_PACKAGE, proxyClass).build();

        javaFile.writeTo(context.getFiler());
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
    private List<MethodSpec> createDelegatingMethods(ComponentModel component, TypeElement interfaceElement) {
        List<MethodSpec> methods = new ArrayList<>();
        for (ExecutableElement method : collectAbstractMethods(interfaceElement)) {
            methods.add(createDelegatingMethod(component, method));
        }
        return methods;
    }

    /**
     * Creates a single delegating method that forwards to the actual instance.
     */
    private MethodSpec createDelegatingMethod(ComponentModel component, ExecutableElement method) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(
                        method.getSimpleName().toString())
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(TypeName.get(method.getReturnType()));

        // Carry over type parameters (e.g. `<T> T unwrap(Class<T>)` from java.sql.Wrapper).
        for (var typeParam : method.getTypeParameters()) {
            methodBuilder.addTypeVariable(TypeVariableName.get(typeParam));
        }

        // Forward declared exceptions so the proxy preserves the interface's `throws` contract.
        for (TypeMirror thrown : method.getThrownTypes()) {
            methodBuilder.addException(TypeName.get(thrown));
        }

        // Add parameters
        List<String> paramNames = new ArrayList<>();
        method.getParameters().forEach(param -> {
            String paramName = param.getSimpleName().toString();
            paramNames.add(paramName);
            methodBuilder.addParameter(TypeName.get(param.asType()), paramName);
        });

        // Generate delegation call
        String getActualCall = generateGetActualInstanceCall(component);
        String params = String.join(", ", paramNames);

        if (method.getReturnType().getKind() == javax.lang.model.type.TypeKind.VOID) {
            methodBuilder.addStatement("$L.$L($L)", getActualCall, method.getSimpleName(), params);
        } else {
            methodBuilder.addStatement("return $L.$L($L)", getActualCall, method.getSimpleName(), params);
        }

        return methodBuilder.build();
    }

    /**
     * Creates delegating methods for a factory-output proxy. Each method body
     * resolves the current scope's value via {@code container.produce_<id>()} and
     * forwards the call.
     */
    private List<MethodSpec> createFactoryDelegatingMethods(FactoryMethodModel factory, TypeElement interfaceElement) {
        List<MethodSpec> methods = new ArrayList<>();
        String delegateCall = "container.produce_" + factory.getFactoryIdentifier() + "()";
        for (ExecutableElement method : collectAbstractMethods(interfaceElement)) {
            methods.add(createFactoryDelegatingMethod(method, delegateCall));
        }
        return methods;
    }

    /**
     * Walks the interface and all its superinterfaces, collecting abstract methods
     * (excluding {@code static} and {@code default}). Deduplicates by a signature
     * key (name plus erasure of parameter types) so that a method re-declared in a
     * subinterface doesn't appear twice.
     */
    private List<ExecutableElement> collectAbstractMethods(TypeElement interfaceElement) {
        LinkedHashMap<String, ExecutableElement> bySig = new LinkedHashMap<>();
        collectInto(interfaceElement, bySig);
        return new ArrayList<>(bySig.values());
    }

    private void collectInto(TypeElement interfaceElement, LinkedHashMap<String, ExecutableElement> sink) {
        for (Element enclosed : interfaceElement.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement method) {
                if (method.getModifiers().contains(Modifier.STATIC)
                        || method.getModifiers().contains(Modifier.DEFAULT)) {
                    continue;
                }
                sink.putIfAbsent(signatureKey(method), method);
            }
        }
        for (TypeMirror superInterface : interfaceElement.getInterfaces()) {
            if (superInterface.getKind() != TypeKind.DECLARED) continue;
            var superElement = ((DeclaredType) superInterface).asElement();
            if (superElement instanceof TypeElement parent) {
                collectInto(parent, sink);
            }
        }
    }

    private String signatureKey(ExecutableElement method) {
        var sb = new StringBuilder(method.getSimpleName().toString()).append('(');
        boolean first = true;
        for (var param : method.getParameters()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(context.getTypeUtils().erasure(param.asType()).toString());
        }
        return sb.append(')').toString();
    }

    private MethodSpec createFactoryDelegatingMethod(ExecutableElement method, String delegateCall) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(
                        method.getSimpleName().toString())
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(TypeName.get(method.getReturnType()));

        // Carry over type parameters (e.g. `<T> T unwrap(Class<T>)` from java.sql.Wrapper).
        for (var typeParam : method.getTypeParameters()) {
            methodBuilder.addTypeVariable(TypeVariableName.get(typeParam));
        }

        // Forward declared exceptions so the proxy preserves the interface's `throws` contract
        // (e.g. java.sql.Connection's checked SQLException).
        for (TypeMirror thrown : method.getThrownTypes()) {
            methodBuilder.addException(TypeName.get(thrown));
        }

        List<String> paramNames = new ArrayList<>();
        method.getParameters().forEach(param -> {
            String paramName = param.getSimpleName().toString();
            paramNames.add(paramName);
            methodBuilder.addParameter(TypeName.get(param.asType()), paramName);
        });

        String params = String.join(", ", paramNames);

        if (method.getReturnType().getKind() == javax.lang.model.type.TypeKind.VOID) {
            methodBuilder.addStatement("$L.$L($L)", delegateCall, method.getSimpleName(), params);
        } else {
            methodBuilder.addStatement("return $L.$L($L)", delegateCall, method.getSimpleName(), params);
        }

        return methodBuilder.build();
    }

    /**
     * Generates the call to get the actual instance from the container.
     * e.g., "container.getCurrentRequestContext()"
     */
    private String generateGetActualInstanceCall(ComponentModel component) {
        String methodName =
                switch (component.getScope()) {
                    case REQUEST -> "getCurrent" + component.getClassName();
                    case EVENT -> "getCurrent" + component.getClassName();
                    default ->
                        throw new IllegalStateException(
                                "Proxy only for REQUEST/EVENT scope, got: " + component.getScope());
                };

        return "container." + methodName + "()";
    }
}
