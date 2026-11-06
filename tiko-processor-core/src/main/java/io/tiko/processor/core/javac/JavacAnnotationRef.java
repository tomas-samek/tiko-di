package io.tiko.processor.core.javac;

import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.Location;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/** A javac {@link AnnotationMirror} as an {@link AnnotationRef}; values include declared defaults. */
record JavacAnnotationRef(AnnotationMirror mirror, Element annotated, JavacModel model) implements AnnotationRef {

    @Override
    public String annotationName() {
        return JavacModel.annotationName(mirror);
    }

    @Override
    public Optional<Object> value(String member) {
        return model.elements().getElementValuesWithDefaults(mirror).entrySet().stream()
                .filter(entry -> entry.getKey().getSimpleName().contentEquals(member))
                .findFirst()
                .map(entry -> convert(entry.getValue(), isClassValued(entry.getKey())));
    }

    /**
     * javac hands an erroneous class literal over as a {@code String} ("&lt;error&gt;") instead
     * of a {@link TypeMirror}; in a {@code Class}-typed member that reads as absent (see
     * {@link AnnotationRef}): an empty list for a single value, left out of an array.
     */
    private Object convert(AnnotationValue value, boolean classValued) {
        var raw = value.getValue();
        return switch (raw) {
            case VariableElement enumConstant -> enumConstant.getSimpleName().toString();
            case TypeMirror type -> model.typeRef(type);
            case AnnotationMirror nested -> model.annotationRef(nested, annotated);
            case List<?> values ->
                values.stream()
                        .map(AnnotationValue.class::cast)
                        .filter(item -> !classValued || item.getValue() instanceof TypeMirror)
                        .map(item -> convert(item, classValued))
                        .toList();
            default -> classValued ? List.of() : raw;
        };
    }

    /** Whether the member is declared {@code Class} or {@code Class[]} (type arguments aside). */
    private static boolean isClassValued(ExecutableElement member) {
        var type = member.getReturnType();
        if (type instanceof ArrayType array) {
            type = array.getComponentType();
        }
        return type instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement element
                && element.getQualifiedName().contentEquals("java.lang.Class");
    }

    @Override
    public Location location() {
        return new JavacLocation(annotated, mirror);
    }

    /** Equal when it is the same annotation on the same element, whichever round's env made it. */
    @Override
    public boolean equals(Object other) {
        return other instanceof JavacAnnotationRef that
                && mirror.equals(that.mirror)
                && annotated.equals(that.annotated);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mirror, annotated);
    }

    @Override
    public String toString() {
        return mirror + " on " + annotated;
    }
}
