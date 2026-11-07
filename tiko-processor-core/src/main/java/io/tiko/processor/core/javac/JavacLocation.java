package io.tiko.processor.core.javac;

import io.tiko.processor.core.model.Location;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/**
 * A javac position: the element, and the annotation on it (with its path there, see
 * {@link JavacAnnotationRef}) when the location is an annotation's. Reported in a later round, it
 * is found again in that round (see {@link #current}).
 */
record JavacLocation(Element element, AnnotationMirror annotation, String annotationPath) implements Location {

    static JavacLocation of(Element element) {
        return new JavacLocation(element, null, null);
    }

    /**
     * The same position among this round's elements. javac makes a class's members and all
     * annotations afresh every round and places a message only on a current one, so a location
     * from an earlier round would otherwise report without a position.
     */
    JavacLocation current(JavacModel model) {
        var currentElement = JavacIdentity.current(element, model.types());
        if (annotation == null) {
            return currentElement == element ? this : of(currentElement);
        }
        var currentAnnotation = JavacIdentity.at(currentElement, annotationPath, model.elements())
                .orElse(annotation);
        return new JavacLocation(currentElement, currentAnnotation, annotationPath);
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
