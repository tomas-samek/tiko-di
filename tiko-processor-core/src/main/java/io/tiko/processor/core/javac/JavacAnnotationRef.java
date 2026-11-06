package io.tiko.processor.core.javac;

import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.Location;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * A javac {@link AnnotationMirror} as an {@link AnnotationRef}; values include declared defaults.
 * {@code path} is where the mirror sits on {@code annotated}: its position among the element's
 * annotations, then {@code /member} or {@code /member[index]} per nesting level, as
 * {@link JavacIdentity#at} reads it.
 */
record JavacAnnotationRef(AnnotationMirror mirror, Element annotated, String path, JavacModel model)
        implements AnnotationRef {

    @Override
    public String annotationName() {
        return JavacModel.annotationName(mirror);
    }

    @Override
    public Optional<Object> value(String member) {
        return entry(member)
                .map(entry -> convert(entry.getValue(), isClassValued(entry.getKey()), path + "/" + member));
    }

    /** Detects the same unresolved values {@link #convert} leaves out. */
    @Override
    public boolean isUnresolved(String member) {
        var entry = entry(member)
                .orElseThrow(
                        () -> new NoSuchElementException("@" + annotationName() + " has no member '" + member + "'"));
        if (!isClassValued(entry.getKey())) {
            return false;
        }
        var raw = entry.getValue().getValue();
        if (raw instanceof List<?> values) {
            return values.stream().map(AnnotationValue.class::cast).anyMatch(item -> !resolvedClass(item.getValue()));
        }
        return !resolvedClass(raw);
    }

    private Optional<Map.Entry<? extends ExecutableElement, ? extends AnnotationValue>> entry(String member) {
        return model.elements().getElementValuesWithDefaults(mirror).entrySet().stream()
                .filter(entry -> entry.getKey().getSimpleName().contentEquals(member))
                .findFirst()
                .map(entry -> entry);
    }

    /**
     * javac hands an erroneous class literal over as a {@code String} ("&lt;error&gt;") instead
     * of a {@link TypeMirror}, or as a {@link TypeMirror} of {@link TypeKind#ERROR}; in a
     * {@code Class}-typed member either reads as absent (see {@link AnnotationRef}): an empty list
     * for a single value, left out of an array.
     */
    private Object convert(AnnotationValue value, boolean classValued, String at) {
        var raw = value.getValue();
        return switch (raw) {
            case VariableElement enumConstant -> enumConstant.getSimpleName().toString();
            case TypeMirror type when type.getKind() == TypeKind.ERROR -> List.of();
            case TypeMirror type -> model.typeRef(type);
            case AnnotationMirror nested -> model.annotationRef(nested, annotated, at);
            case List<?> values -> convertAll(values, classValued, at);
            default -> classValued ? List.of() : raw;
        };
    }

    /** An array's items; a nested annotation is at its position in the array. */
    private List<Object> convertAll(List<?> values, boolean classValued, String at) {
        var items = new ArrayList<>();
        for (var i = 0; i < values.size(); i++) {
            var item = (AnnotationValue) values.get(i);
            if (!classValued || resolvedClass(item.getValue())) {
                items.add(convert(item, classValued, at + "[" + i + "]"));
            }
        }
        return List.copyOf(items);
    }

    private static boolean resolvedClass(Object value) {
        return value instanceof TypeMirror type && type.getKind() != TypeKind.ERROR;
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
        return new JavacLocation(annotated, mirror, path);
    }

    /**
     * Equal when it is the same annotation on the same declaration, whichever round's env made it:
     * javac makes the mirrors afresh every round, so one from another round is matched by its
     * declaration and its {@code path} there.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof JavacAnnotationRef that
                && path.equals(that.path)
                && JavacIdentity.same(annotated, that.annotated, model.types());
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, JavacIdentity.hash(annotated));
    }

    @Override
    public String toString() {
        return mirror + " on " + annotated;
    }
}
