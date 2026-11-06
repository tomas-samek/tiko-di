package io.tiko.processor.core.javac;

import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.ParamDecl;
import io.tiko.processor.core.model.TypeRef;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.VariableElement;

/** A javac parameter {@link VariableElement} as a {@link ParamDecl}. Java has no default arguments. */
record JavacParamDecl(VariableElement element, JavacModel model) implements ParamDecl {

    @Override
    public String name() {
        return element.getSimpleName().toString();
    }

    @Override
    public TypeRef type() {
        return model.typeRef(element.asType());
    }

    @Override
    public boolean hasDefaultValue() {
        return false;
    }

    @Override
    public List<AnnotationRef> annotations() {
        return model.annotations(element);
    }

    @Override
    public Optional<AnnotationRef> annotation(String fqn) {
        return model.annotation(element, fqn);
    }

    @Override
    public Location location() {
        return JavacLocation.of(element);
    }
}
