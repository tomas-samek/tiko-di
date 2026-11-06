package io.tiko.processor.core.javac;

import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.Location;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.VariableElement;
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
                .map(entry -> convert(entry.getValue()));
    }

    private Object convert(AnnotationValue value) {
        var raw = value.getValue();
        return switch (raw) {
            case VariableElement enumConstant -> enumConstant.getSimpleName().toString();
            case TypeMirror type -> model.typeRef(type);
            case AnnotationMirror nested -> model.annotationRef(nested, annotated);
            case List<?> values ->
                values.stream().map(item -> convert((AnnotationValue) item)).toList();
            default -> raw;
        };
    }

    @Override
    public Location location() {
        return new JavacLocation(annotated, mirror);
    }
}
