package io.tiko.processor.model;

import java.util.Optional;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Represents a dependency to be injected.
 * This is a parameter in a constructor or factory method.
 */
public final class DependencyModel {

    /**
     * FQN of the framework event bus. It is injectable into a {@code @Component} constructor
     * or a {@code @Produces} method as a built-in dependency (#314) — resolved from the
     * container, not from a user-declared bean. {@code io.tiko.Container} is intentionally
     * NOT in this set: injecting the container is service location, whereas {@code EventBus}
     * is a legitimate collaborator interface for imperative {@code publish(...)}.
     */
    public static final String EVENT_BUS_TYPE = "io.tiko.EventBus";

    private final VariableElement parameter;
    private final TypeMirror type;
    private final String typeName;
    private final String qualifier; // From @Named annotation
    private final boolean isProvider; // Provider<T> wrapper
    private final boolean isPicker; // Picker<T> wrapper
    private final TypeMirror unwrappedType; // T in Provider<T> or Picker<T>
    private final TypeMirror pickedType; // From @Pick annotation (nullable)
    private final String pickedTypeName; // Qualified name of @Pick value (nullable)

    private DependencyModel(Builder builder) {
        this.parameter = builder.parameter;
        this.type = builder.type;
        this.typeName = builder.typeName;
        this.qualifier = builder.qualifier;
        this.isProvider = builder.isProvider;
        this.isPicker = builder.isPicker;
        this.unwrappedType = builder.unwrappedType;
        this.pickedType = builder.pickedType;
        this.pickedTypeName = builder.pickedTypeName;
    }

    public static Builder builder() {
        return new Builder();
    }

    public VariableElement getParameter() {
        return parameter;
    }

    public TypeMirror getType() {
        return type;
    }

    public String getTypeName() {
        return typeName;
    }

    public Optional<String> getQualifier() {
        return Optional.ofNullable(qualifier).filter(q -> !q.isEmpty());
    }

    public boolean isProvider() {
        return isProvider;
    }

    public boolean isPicker() {
        return isPicker;
    }

    public Optional<TypeMirror> getUnwrappedType() {
        return Optional.ofNullable(unwrappedType);
    }

    public Optional<TypeMirror> getPickedType() {
        return Optional.ofNullable(pickedType);
    }

    public Optional<String> getPickedTypeName() {
        return Optional.ofNullable(pickedTypeName);
    }

    public boolean isPicked() {
        return pickedTypeName != null;
    }

    /**
     * True when this is a plain injection of the built-in {@link #EVENT_BUS_TYPE} service —
     * either {@code EventBus} directly or {@code Provider<EventBus>}, unqualified and not
     * {@code @Pick}ed. A qualified {@code @Named EventBus} is excluded (there is one bus),
     * so it falls through to the normal missing-dependency path.
     */
    public boolean isEventBus() {
        return !isPicker && pickedTypeName == null && EVENT_BUS_TYPE.equals(getDependencyKey());
    }

    /**
     * Returns the key to look up this dependency in the container.
     *
     * <p>Format:
     * <ul>
     *   <li>{@code @Pick(X.class)} → {@code X.qualifiedName}, optionally suffixed with
     *       {@code #qualifier} when {@code @Named} is also present. The pair selects
     *       a provider whose impl class is {@code X} and whose name is {@code qualifier}
     *       — the standard composition for picking among multiple {@code @Produces}
     *       methods that return the same type with different names.</li>
     *   <li>Picker dep → {@code typeName} (the picker base type T). Pickers are
     *       constructed inline by codegen, not looked up by provider key — this is
     *       only used for diagnostic messages and the "≥1 impl exists" check.</li>
     *   <li>otherwise → {@code typeName} or {@code typeName#qualifier}.</li>
     * </ul>
     */
    public String getDependencyKey() {
        if (pickedTypeName != null) {
            return getQualifier().map(q -> pickedTypeName + "#" + q).orElse(pickedTypeName);
        }
        if (isPicker) {
            return unwrappedType.toString();
        }
        String baseType = isProvider ? unwrappedType.toString() : typeName;
        return getQualifier().map(q -> baseType + "#" + q).orElse(baseType);
    }

    /**
     * Returns the parameter name for use in generated code.
     */
    public String getParameterName() {
        return parameter.getSimpleName().toString();
    }

    public static final class Builder {
        private VariableElement parameter;
        private TypeMirror type;
        private String typeName;
        private String qualifier = "";
        private boolean isProvider = false;
        private boolean isPicker = false;
        private TypeMirror unwrappedType;
        private TypeMirror pickedType;
        private String pickedTypeName;

        private Builder() {}

        public Builder parameter(VariableElement parameter) {
            this.parameter = parameter;
            return this;
        }

        public Builder type(TypeMirror type) {
            this.type = type;
            return this;
        }

        public Builder typeName(String typeName) {
            this.typeName = typeName;
            return this;
        }

        public Builder qualifier(String qualifier) {
            this.qualifier = qualifier;
            return this;
        }

        public Builder isProvider(boolean isProvider) {
            this.isProvider = isProvider;
            return this;
        }

        public Builder isPicker(boolean isPicker) {
            this.isPicker = isPicker;
            return this;
        }

        public Builder unwrappedType(TypeMirror unwrappedType) {
            this.unwrappedType = unwrappedType;
            return this;
        }

        public Builder pickedType(TypeMirror pickedType) {
            this.pickedType = pickedType;
            return this;
        }

        public Builder pickedTypeName(String pickedTypeName) {
            this.pickedTypeName = pickedTypeName;
            return this;
        }

        public DependencyModel build() {
            if (parameter == null || type == null || typeName == null) {
                throw new IllegalStateException("Parameter, type, and typeName are required");
            }
            return new DependencyModel(this);
        }
    }
}
