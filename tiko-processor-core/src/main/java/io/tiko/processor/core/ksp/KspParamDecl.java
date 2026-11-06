package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.symbol.KSDeclaration;
import com.google.devtools.ksp.symbol.KSValueParameter;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.ParamDecl;
import io.tiko.processor.core.model.TypeRef;
import java.util.List;
import java.util.Optional;

/**
 * A {@link KSValueParameter} as a {@link ParamDecl}, found again by its position on its function.
 * Equal by that position, whichever round made it.
 */
final class KspParamDecl implements ParamDecl {

    private final KspRef<KSValueParameter> ref;
    private final String key;
    private final String enclosingClass;
    private final KspModel model;

    KspParamDecl(KspRef<KSValueParameter> ref, String key, String enclosingClass, KspModel model) {
        this.ref = ref;
        this.key = key;
        this.enclosingClass = enclosingClass;
        this.model = model;
    }

    /** The source name; {@code argN} for a classpath parameter compiled without names. */
    @Override
    public String name() {
        var name = ref.get().getName();
        return name == null ? "arg" + key.substring(key.lastIndexOf('(') + 1) : name.asString();
    }

    /** The declared type; for a vararg parameter the array type of its JVM signature, as javac reports it. */
    @Override
    public TypeRef type() {
        var parameter = ref.get();
        var javaDeclared = parameter.getParent() instanceof KSDeclaration owner && KspModel.isJava(owner);
        if (parameter.isVararg()) {
            return model.typeRef(ref.derive(p -> KspTypeNames.varargArray(p, model.resolver())), javaDeclared);
        }
        return model.typeRef(ref.derive(p -> p.getType().resolve()), javaDeclared);
    }

    @Override
    public boolean hasDefaultValue() {
        return ref.get().getHasDefault();
    }

    @Override
    public List<AnnotationRef> annotations() {
        return model.annotations(ref, key, enclosingClass);
    }

    @Override
    public Optional<AnnotationRef> annotation(String fqn) {
        return KspAnnotationRef.first(ref, fqn, key, enclosingClass, model);
    }

    @Override
    public List<AnnotationRef> annotationsByType(String annotationFqn) {
        return KspAnnotationRef.byType(ref, annotationFqn, key, enclosingClass, model);
    }

    @Override
    public Location location() {
        return new KspLocation(ref, enclosingClass);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KspParamDecl that && key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }
}
