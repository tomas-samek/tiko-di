package io.tiko.processor.model;

import io.tiko.Scope;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.TypeRef;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a @Produces factory method.
 * Can be either an instance method (in a component class) or a static method.
 */
public final class FactoryMethodModel {

    private final MethodDecl method;
    private final ClassDecl declaringClass;
    private final String methodName;
    private final TypeRef returnType;
    private final String returnTypeName;
    private final Scope scope;
    private final String name; // Optional qualifier
    private final List<String> profiles;
    private final List<DependencyModel> dependencies;
    private final boolean isStatic;
    private final boolean autoCloseable;
    private final boolean requiresProxy;

    private FactoryMethodModel(Builder builder) {
        this.method = builder.method;
        this.declaringClass = builder.declaringClass;
        this.methodName = builder.methodName;
        this.returnType = builder.returnType;
        this.returnTypeName = builder.returnTypeName;
        this.scope = builder.scope;
        this.name = builder.name;
        this.profiles = List.copyOf(builder.profiles);
        this.dependencies = List.copyOf(builder.dependencies);
        this.isStatic = builder.isStatic;
        this.autoCloseable = builder.autoCloseable;
        this.requiresProxy = builder.requiresProxy;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The {@code @Produces} method; generated code calls it by {@link MethodDecl#jvmName()}. */
    public MethodDecl getMethod() {
        return method;
    }

    public ClassDecl getDeclaringClass() {
        return declaringClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public TypeRef getReturnType() {
        return returnType;
    }

    public String getReturnTypeName() {
        return returnTypeName;
    }

    public Scope getScope() {
        return scope;
    }

    public String getName() {
        return name;
    }

    public List<String> getProfiles() {
        return profiles;
    }

    public List<DependencyModel> getDependencies() {
        return dependencies;
    }

    public boolean isStatic() {
        return isStatic;
    }

    /**
     * True when the produced type implements {@link AutoCloseable}. The container
     * codegen invokes {@code close()} on the produced bean at scope teardown — this
     * lets {@code @Produces} factories return third-party closeables (data sources,
     * HTTP clients, Kafka producers) without the user having to write a wrapper
     * {@code @Component} just for cleanup.
     */
    public boolean isAutoCloseable() {
        return autoCloseable;
    }

    /**
     * Returns the unique key for the component produced by this factory.
     * Format: returnTypeName or returnTypeName#name
     */
    public String getComponentKey() {
        if (name != null && !name.isEmpty()) {
            return returnTypeName + "#" + name;
        }
        return returnTypeName;
    }

    /**
     * Returns the factory identifier for code generation.
     * Format: DeclaringClass_methodName
     */
    public String getFactoryIdentifier() {
        return declaringClass.className().simpleName() + "_" + methodName;
    }

    /**
     * True when this factory's output needs an auto-proxy class so that a
     * longer-lived consumer can hold a stable reference that resolves to
     * the current scope's value on every method call. Triggered when the
     * factory's scope is REQUEST or EVENT and its return type is an
     * interface (proxying a concrete class would require bytecode tricks
     * Tiko deliberately avoids). The decision to actually <em>use</em> the
     * proxy at a particular consumer site is the consumer side's call —
     * the proxy class itself is generated unconditionally when this flag
     * is true.
     */
    public boolean requiresProxy() {
        return requiresProxy;
    }

    /**
     * Whether a component in {@code consumerScope} receives this factory's output through its
     * generated proxy instead of the value itself: the factory needs a proxy and the consumer
     * outlives the factory's scope. The one rule the factory generator and the validators share.
     */
    public boolean isProxiedFor(Scope consumerScope) {
        return requiresProxy && scopeLevel(consumerScope) < scopeLevel(scope);
    }

    /** Lower is longer-lived. */
    private static int scopeLevel(Scope scope) {
        return switch (scope) {
            case SINGLETON -> 0;
            case EVENT -> 1;
            case PROTOTYPE -> 2;
        };
    }

    public static final class Builder {
        private MethodDecl method;
        private ClassDecl declaringClass;
        private String methodName;
        private TypeRef returnType;
        private String returnTypeName;
        private Scope scope = Scope.PROTOTYPE;
        private String name = "";
        private List<String> profiles = new ArrayList<>();
        private List<DependencyModel> dependencies = new ArrayList<>();
        private boolean isStatic = false;
        private boolean autoCloseable = false;
        private boolean requiresProxy = false;

        private Builder() {}

        public Builder method(MethodDecl method) {
            this.method = method;
            this.isStatic = method.isStatic();
            return this;
        }

        public Builder declaringClass(ClassDecl declaringClass) {
            this.declaringClass = declaringClass;
            return this;
        }

        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder returnType(TypeRef returnType) {
            this.returnType = returnType;
            return this;
        }

        public Builder returnTypeName(String returnTypeName) {
            this.returnTypeName = returnTypeName;
            return this;
        }

        public Builder scope(Scope scope) {
            this.scope = scope;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder profiles(List<String> profiles) {
            this.profiles = new ArrayList<>(profiles);
            return this;
        }

        public Builder dependencies(List<DependencyModel> dependencies) {
            this.dependencies = new ArrayList<>(dependencies);
            return this;
        }

        public Builder addDependency(DependencyModel dependency) {
            this.dependencies.add(dependency);
            return this;
        }

        public Builder isStatic(boolean isStatic) {
            this.isStatic = isStatic;
            return this;
        }

        public Builder autoCloseable(boolean autoCloseable) {
            this.autoCloseable = autoCloseable;
            return this;
        }

        public Builder requiresProxy(boolean requiresProxy) {
            this.requiresProxy = requiresProxy;
            return this;
        }

        public FactoryMethodModel build() {
            if (method == null || declaringClass == null || returnType == null) {
                throw new IllegalStateException("Method, declaringClass, and returnType are required");
            }
            return new FactoryMethodModel(this);
        }
    }
}
