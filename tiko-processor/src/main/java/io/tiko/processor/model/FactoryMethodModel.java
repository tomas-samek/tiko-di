package io.tiko.processor.model;

import io.tiko.Scope;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a @Produces factory method.
 * Can be either an instance method (in a component class) or a static method.
 */
public final class FactoryMethodModel {

    private final ExecutableElement methodElement;
    private final TypeElement declaringClass;
    private final String methodName;
    private final TypeMirror returnType;
    private final String returnTypeName;
    private final Scope scope;
    private final String name;  // Optional qualifier
    private final List<String> profiles;
    private final List<DependencyModel> dependencies;
    private final boolean isStatic;
    private final boolean autoCloseable;

    private FactoryMethodModel(Builder builder) {
        this.methodElement = builder.methodElement;
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
    }

    public static Builder builder() {
        return new Builder();
    }

    public ExecutableElement getMethodElement() {
        return methodElement;
    }

    public TypeElement getDeclaringClass() {
        return declaringClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public TypeMirror getReturnType() {
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
        return declaringClass.getSimpleName() + "_" + methodName;
    }

    public static final class Builder {
        private ExecutableElement methodElement;
        private TypeElement declaringClass;
        private String methodName;
        private TypeMirror returnType;
        private String returnTypeName;
        private Scope scope = Scope.PROTOTYPE;
        private String name = "";
        private List<String> profiles = new ArrayList<>();
        private List<DependencyModel> dependencies = new ArrayList<>();
        private boolean isStatic = false;
        private boolean autoCloseable = false;

        private Builder() {
        }

        public Builder methodElement(ExecutableElement methodElement) {
            this.methodElement = methodElement;
            this.isStatic = methodElement.getModifiers().contains(Modifier.STATIC);
            return this;
        }

        public Builder declaringClass(TypeElement declaringClass) {
            this.declaringClass = declaringClass;
            return this;
        }

        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder returnType(TypeMirror returnType) {
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

        public FactoryMethodModel build() {
            if (methodElement == null || declaringClass == null || returnType == null) {
                throw new IllegalStateException("MethodElement, declaringClass, and returnType are required");
            }
            return new FactoryMethodModel(this);
        }
    }
}
