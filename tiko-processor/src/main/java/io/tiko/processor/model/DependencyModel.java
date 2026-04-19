package io.tiko.processor.model;

import io.tiko.Scope;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.Optional;

/**
 * Represents a dependency to be injected.
 * This is a parameter in a constructor or factory method.
 */
public final class DependencyModel {

    private final VariableElement parameter;
    private final TypeMirror type;
    private final String typeName;
    private final String qualifier;  // From @Named annotation
    private final boolean isProvider;  // Provider<T> wrapper
    private final TypeMirror unwrappedType;  // T in Provider<T>

    private DependencyModel(Builder builder) {
        this.parameter = builder.parameter;
        this.type = builder.type;
        this.typeName = builder.typeName;
        this.qualifier = builder.qualifier;
        this.isProvider = builder.isProvider;
        this.unwrappedType = builder.unwrappedType;
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

    public Optional<TypeMirror> getUnwrappedType() {
        return Optional.ofNullable(unwrappedType);
    }

    /**
     * Returns the key to look up this dependency in the container.
     * Format: typeName or typeName#qualifier
     */
    public String getDependencyKey() {
        String baseType = isProvider ? unwrappedType.toString() : typeName;
        return getQualifier()
                .map(q -> baseType + "#" + q)
                .orElse(baseType);
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
        private TypeMirror unwrappedType;

        private Builder() {
        }

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

        public Builder unwrappedType(TypeMirror unwrappedType) {
            this.unwrappedType = unwrappedType;
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
