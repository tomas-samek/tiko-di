package io.tiko.processor.model;

import io.tiko.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

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
    private final String name; // Optional qualifier
    private final List<String> profiles;
    private final List<DependencyModel> dependencies;
    private final ExecutableElement constructor;
    private final List<ExecutableElement> postConstructMethods;
    private final List<ExecutableElement> preDestroyMethods;
    private final TypeMirror implementedInterface; // For proxy generation (nullable)
    private final boolean requiresProxy;
    private final ExecutableElement staticFactoryMethod; // Optional self-@Produces (nullable)
    private final boolean autoCloseable;
    private final List<TypeMirror> exposeTypes; // @Component(expose = {...}); empty = permissive default
    private final boolean exposeSelf; // @Component(exposeSelf = ...); defaults to true

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
        this.staticFactoryMethod = builder.staticFactoryMethod;
        this.autoCloseable = builder.autoCloseable;
        this.exposeTypes = List.copyOf(builder.exposeTypes);
        this.exposeSelf = builder.exposeSelf;
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

    public Optional<ExecutableElement> getStaticFactoryMethod() {
        return Optional.ofNullable(staticFactoryMethod);
    }

    /**
     * True when the component (or any supertype) implements {@link AutoCloseable} and
     * does not declare an explicit {@code @PreDestroy}. The container codegen treats
     * this as an implicit {@code @PreDestroy close()} hook so users get cleanup for
     * free when their bean is naturally closeable. An explicit {@code @PreDestroy}
     * always wins — this flag is false in that case to avoid double-cleanup.
     */
    public boolean isAutoCloseable() {
        return autoCloseable;
    }

    /**
     * True when this component has any destroy hook that should run at scope teardown —
     * either an explicit {@code @PreDestroy} method or the implicit {@code AutoCloseable}
     * cleanup. Used by codegen to decide whether to emit teardown loops.
     */
    public boolean hasDestroyHook() {
        return !preDestroyMethods.isEmpty() || autoCloseable;
    }

    /**
     * Returns the unique key for this component (qualified name + optional name qualifier).
     */
    public String getComponentKey() {
        return getName().map(n -> qualifiedName + "#" + n).orElse(qualifiedName);
    }

    /**
     * The user-declared {@code @Component(expose = {...})} list. Empty list means
     * "permissive default" — the bean is exposed under every implemented interface (and
     * under itself if {@link #isExposeSelf()} is true). Non-empty list means
     * "explicit restriction" — only the listed types route to this bean.
     */
    public List<TypeMirror> getExposeTypes() {
        return exposeTypes;
    }

    /** True when {@link #getExposeTypes()} is non-empty (i.e. user opted into restriction). */
    public boolean isExposeRestricted() {
        return !exposeTypes.isEmpty();
    }

    /**
     * Whether the concrete impl class itself routes via {@code container.get(MyClass.class)}.
     * Defaults to {@code true}. Independent of {@link #getExposeTypes()}.
     */
    public boolean isExposeSelf() {
        return exposeSelf;
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
        private ExecutableElement staticFactoryMethod;
        private boolean autoCloseable = false;
        private List<TypeMirror> exposeTypes = new ArrayList<>();
        private boolean exposeSelf = true;

        private Builder() {}

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

        public Builder staticFactoryMethod(ExecutableElement staticFactoryMethod) {
            this.staticFactoryMethod = staticFactoryMethod;
            return this;
        }

        public Builder autoCloseable(boolean autoCloseable) {
            this.autoCloseable = autoCloseable;
            return this;
        }

        public Builder exposeTypes(List<TypeMirror> exposeTypes) {
            this.exposeTypes = new ArrayList<>(exposeTypes);
            return this;
        }

        public Builder exposeSelf(boolean exposeSelf) {
            this.exposeSelf = exposeSelf;
            return this;
        }

        public ComponentModel build() {
            if (typeElement == null || qualifiedName == null) {
                throw new IllegalStateException("TypeElement and qualifiedName are required");
            }
            if (constructor == null && staticFactoryMethod == null) {
                throw new IllegalStateException("Either constructor or staticFactoryMethod is required");
            }
            return new ComponentModel(this);
        }
    }
}
