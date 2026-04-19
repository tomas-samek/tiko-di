package io.tiko.processor.model;

import io.tiko.Scope;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Internal model representing a @Component annotated class.
 * Contains all metadata needed for code generation and validation.
 */
public final class ComponentModel {

    private final TypeElement typeElement;
    private final String packageName;
    private final String className;
    private final String qualifiedName;
    private final Scope scope;
    private final String name;  // Optional qualifier
    private final List<String> profiles;
    private final List<DependencyModel> dependencies;
    private final ExecutableElement constructor;
    private final List<ExecutableElement> postConstructMethods;
    private final List<ExecutableElement> preDestroyMethods;
    private final TypeMirror implementedInterface;  // For proxy generation (nullable)
    private final boolean requiresProxy;

    private ComponentModel(Builder builder) {
        this.typeElement = builder.typeElement;
        this.packageName = builder.packageName;
        this.className = builder.className;
        this.qualifiedName = builder.qualifiedName;
        this.scope = builder.scope;
        this.name = builder.name;
        this.profiles = List.copyOf(builder.profiles);
        this.dependencies = List.copyOf(builder.dependencies);
        this.constructor = builder.constructor;
        this.postConstructMethods = List.copyOf(builder.postConstructMethods);
        this.preDestroyMethods = List.copyOf(builder.preDestroyMethods);
        this.implementedInterface = builder.implementedInterface;
        this.requiresProxy = builder.requiresProxy;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters
    public TypeElement getTypeElement() {
        return typeElement;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getClassName() {
        return className;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public Scope getScope() {
        return scope;
    }

    public Optional<String> getName() {
        return Optional.ofNullable(name).filter(n -> !n.isEmpty());
    }

    public List<String> getProfiles() {
        return profiles;
    }

    public List<DependencyModel> getDependencies() {
        return dependencies;
    }

    public ExecutableElement getConstructor() {
        return constructor;
    }

    public List<ExecutableElement> getPostConstructMethods() {
        return postConstructMethods;
    }

    public List<ExecutableElement> getPreDestroyMethods() {
        return preDestroyMethods;
    }

    public Optional<TypeMirror> getImplementedInterface() {
        return Optional.ofNullable(implementedInterface);
    }

    public boolean requiresProxy() {
        return requiresProxy;
    }

    /**
     * Returns the unique key for this component (qualified name + optional name qualifier).
     */
    public String getComponentKey() {
        return getName()
                .map(n -> qualifiedName + "#" + n)
                .orElse(qualifiedName);
    }

    public static final class Builder {
        private TypeElement typeElement;
        private String packageName;
        private String className;
        private String qualifiedName;
        private Scope scope = Scope.PROTOTYPE;
        private String name = "";
        private List<String> profiles = new ArrayList<>();
        private List<DependencyModel> dependencies = new ArrayList<>();
        private ExecutableElement constructor;
        private List<ExecutableElement> postConstructMethods = new ArrayList<>();
        private List<ExecutableElement> preDestroyMethods = new ArrayList<>();
        private TypeMirror implementedInterface;
        private boolean requiresProxy = false;

        private Builder() {
        }

        public Builder typeElement(TypeElement typeElement) {
            this.typeElement = typeElement;
            return this;
        }

        public Builder packageName(String packageName) {
            this.packageName = packageName;
            return this;
        }

        public Builder className(String className) {
            this.className = className;
            return this;
        }

        public Builder qualifiedName(String qualifiedName) {
            this.qualifiedName = qualifiedName;
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

        public Builder constructor(ExecutableElement constructor) {
            this.constructor = constructor;
            return this;
        }

        public Builder postConstructMethods(List<ExecutableElement> methods) {
            this.postConstructMethods = new ArrayList<>(methods);
            return this;
        }

        public Builder addPostConstructMethod(ExecutableElement method) {
            this.postConstructMethods.add(method);
            return this;
        }

        public Builder preDestroyMethods(List<ExecutableElement> methods) {
            this.preDestroyMethods = new ArrayList<>(methods);
            return this;
        }

        public Builder addPreDestroyMethod(ExecutableElement method) {
            this.preDestroyMethods.add(method);
            return this;
        }

        public Builder implementedInterface(TypeMirror implementedInterface) {
            this.implementedInterface = implementedInterface;
            return this;
        }

        public Builder requiresProxy(boolean requiresProxy) {
            this.requiresProxy = requiresProxy;
            return this;
        }

        public ComponentModel build() {
            if (typeElement == null || qualifiedName == null || constructor == null) {
                throw new IllegalStateException("TypeElement, qualifiedName, and constructor are required");
            }
            return new ComponentModel(this);
        }
    }
}
