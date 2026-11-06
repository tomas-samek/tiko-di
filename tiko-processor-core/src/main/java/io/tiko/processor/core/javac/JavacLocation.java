package io.tiko.processor.core.javac;

import io.tiko.processor.core.model.Location;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/** A javac position: the element, and the annotation on it when the location is an annotation's. */
record JavacLocation(Element element, AnnotationMirror annotation) implements Location {

    static JavacLocation of(Element element) {
        return new JavacLocation(element, null);
    }

    /** Walks {@link Element#getEnclosingElement()} up to the first {@link TypeElement}. */
    @Override
    public Optional<String> enclosingClassName() {
        var cursor = element;
        while (cursor != null && !(cursor instanceof TypeElement)) {
            cursor = cursor.getEnclosingElement();
        }
        return cursor instanceof TypeElement type
                ? Optional.of(type.getQualifiedName().toString())
                : Optional.empty();
    }
}
