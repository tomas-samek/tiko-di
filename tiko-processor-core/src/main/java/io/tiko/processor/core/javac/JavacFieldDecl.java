package io.tiko.processor.core.javac;

import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.TypeRef;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;

/**
 * A javac field ({@link javax.lang.model.element.VariableElement}) or record component
 * ({@link javax.lang.model.element.RecordComponentElement}) as a {@link FieldDecl}.
 */
record JavacFieldDecl(Element element, JavacModel model) implements FieldDecl {

    @Override
    public String name() {
        return element.getSimpleName().toString();
    }

    @Override
    public TypeRef type() {
        return model.typeRef(element.asType());
    }

    @Override
    public boolean isStatic() {
        return element.getModifiers().contains(Modifier.STATIC);
    }

    @Override
    public List<AnnotationRef> annotations() {
        return model.annotations(element);
    }

    @Override
    public Location location() {
        return JavacLocation.of(element);
    }

    /**
     * Equal when it is the same field, record component or enum constant, whichever round's env
     * made it: matched by class, kind and name (see {@link JavacIdentity}), so a record component
     * and its private field stay apart.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof JavacFieldDecl that && JavacIdentity.same(element, that.element, model.types());
    }

    @Override
    public int hashCode() {
        return JavacIdentity.hash(element);
    }

    @Override
    public String toString() {
        return element.toString();
    }
}
