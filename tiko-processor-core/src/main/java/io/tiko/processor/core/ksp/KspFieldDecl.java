package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.symbol.ClassKind;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSDeclaration;
import com.google.devtools.ksp.symbol.KSPropertyDeclaration;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.Modifier;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.TypeRef;
import java.util.List;

/**
 * A field as a {@link FieldDecl}: a Kotlin property with a backing field, a Java field (a record
 * component's field included), or an enum entry. Found again by name on its class; equal by that
 * name, whichever round made it.
 */
final class KspFieldDecl implements FieldDecl {

    private final KspRef<KSDeclaration> ref;
    private final String key;
    private final String enclosingClass;
    private final KspClassDecl owner;
    private final KspModel model;

    KspFieldDecl(KspRef<KSDeclaration> ref, String key, String enclosingClass, KspClassDecl owner, KspModel model) {
        this.ref = ref;
        this.key = key;
        this.enclosingClass = enclosingClass;
        this.owner = owner;
        this.model = model;
    }

    @Override
    public String name() {
        return ref.get().getSimpleName().asString();
    }

    /** The property's type; for an enum entry, the enum's type. */
    @Override
    public TypeRef type() {
        return model.typeRef(ref.derive(KspFieldDecl::typeOf));
    }

    private static KSType typeOf(KSDeclaration declaration) {
        return switch (declaration) {
            case KSPropertyDeclaration property -> property.getType().resolve();
            case KSClassDeclaration entry -> ((KSClassDeclaration) entry.getParentDeclaration()).asType(List.of());
            default -> throw new IllegalStateException("Not a field: " + declaration);
        };
    }

    /**
     * Static on the JVM: a Java {@code static} field, an enum entry, a {@code const}, or a property
     * of an object or companion (whose backing field is static).
     */
    @Override
    public boolean isStatic() {
        var declaration = ref.get();
        if (declaration instanceof KSClassDeclaration entry) {
            return entry.getClassKind() == ClassKind.ENUM_ENTRY;
        }
        if (KspModel.isJava(declaration)) {
            return declaration.getModifiers().contains(Modifier.JAVA_STATIC);
        }
        var ownerKind = owner.kind();
        return declaration.getModifiers().contains(Modifier.CONST)
                || ownerKind == DeclKind.KOTLIN_OBJECT
                || ownerKind == DeclKind.KOTLIN_COMPANION;
    }

    @Override
    public List<AnnotationRef> annotations() {
        return model.annotations(ref, key, enclosingClass);
    }

    @Override
    public Location location() {
        return new KspLocation(ref, enclosingClass);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KspFieldDecl that && key.equals(that.key);
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
