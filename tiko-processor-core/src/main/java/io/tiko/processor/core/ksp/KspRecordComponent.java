package io.tiko.processor.core.ksp;

import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.ParamDecl;
import io.tiko.processor.core.model.TypeRef;
import java.util.List;

/**
 * A Java record component as a {@link FieldDecl}, read off the canonical constructor's parameter
 * (KSP shows no record fields). Equal by its key, whichever round made it.
 */
record KspRecordComponent(ParamDecl parameter, String key) implements FieldDecl {

    @Override
    public String name() {
        return parameter.name();
    }

    @Override
    public TypeRef type() {
        return parameter.type();
    }

    /** A record component is never static. */
    @Override
    public boolean isStatic() {
        return false;
    }

    @Override
    public List<AnnotationRef> annotations() {
        return parameter.annotations();
    }

    @Override
    public Location location() {
        return parameter.location();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KspRecordComponent that && key.equals(that.key);
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
