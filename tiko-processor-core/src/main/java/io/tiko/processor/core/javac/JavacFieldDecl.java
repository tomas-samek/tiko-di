package io.tiko.processor.core.javac;

import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.TypeRef;
import java.util.List;
import javax.lang.model.element.VariableElement;

/** A javac field {@link VariableElement} as a {@link FieldDecl}. */
record JavacFieldDecl(VariableElement element, JavacModel model) implements FieldDecl {

    @Override
    public String name() {
        return element.getSimpleName().toString();
    }

    @Override
    public TypeRef type() {
        return model.typeRef(element.asType());
    }

    @Override
    public List<AnnotationRef> annotations() {
        return model.annotations(element);
    }

    @Override
    public Location location() {
        return JavacLocation.of(element);
    }

    /** Equal when wrapping the same element, whichever round's env made it. */
    @Override
    public boolean equals(Object other) {
        return other instanceof JavacFieldDecl that && element.equals(that.element);
    }

    @Override
    public int hashCode() {
        return element.hashCode();
    }

    @Override
    public String toString() {
        return element.toString();
    }
}
