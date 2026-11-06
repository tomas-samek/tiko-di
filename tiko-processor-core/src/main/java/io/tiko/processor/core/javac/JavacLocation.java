package io.tiko.processor.core.javac;

import io.tiko.processor.core.model.Location;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;

/** A javac position: the element, and the annotation on it when the location is an annotation's. */
record JavacLocation(Element element, AnnotationMirror annotation) implements Location {

    static JavacLocation of(Element element) {
        return new JavacLocation(element, null);
    }
}
