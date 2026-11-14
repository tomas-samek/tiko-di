package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.symbol.ClassKind;
import com.google.devtools.ksp.symbol.KSAnnotated;
import com.google.devtools.ksp.symbol.KSAnnotation;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.Variance;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.TypeRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import kotlin.sequences.SequencesKt;

/**
 * A {@link KSAnnotation} as an {@link AnnotationRef}. KSP's arguments already include the declared
 * defaults; values are read afresh in each round through the current symbol: an enum constant
 * becomes its name, a class literal a raw {@link TypeRef} (found again by class name), an array a
 * {@code List<Object>}, a nested annotation an {@code AnnotationRef}. A class literal that does not
 * resolve reads as absent and is reported by {@link #isUnresolved(String)}.
 *
 * <p>Equal when it is the same annotation (by position) on the same declaration, whichever round
 * made it.
 */
final class KspAnnotationRef implements AnnotationRef {

    private static final String REPEATABLE = "java.lang.annotation.Repeatable";
    private static final String VALUE = "value";

    private final KspRef<KSAnnotation> node;
    private final String annotationName;
    private final String key;
    private final String enclosingClass;
    private final KspModel model;
    private Values values;

    private KspAnnotationRef(
            KspRef<KSAnnotation> node, String annotationName, String key, String enclosingClass, KspModel model) {
        this.node = node;
        this.annotationName = annotationName;
        this.key = key;
        this.enclosingClass = enclosingClass;
        this.model = model;
    }

    /** Every annotation on {@code annotated}, in source order. */
    static List<AnnotationRef> all(
            KspRef<? extends KSAnnotated> annotated, String ownerKey, String enclosingClass, KspModel model) {
        var annotations = SequencesKt.toList(annotated.get().getAnnotations());
        var refs = new ArrayList<AnnotationRef>();
        for (var i = 0; i < annotations.size(); i++) {
            refs.add(at(annotated, i, annotations.get(i), ownerKey, enclosingClass, model));
        }
        return List.copyOf(refs);
    }

    /** The first annotation of the type on {@code annotated}. */
    static Optional<AnnotationRef> first(
            KspRef<? extends KSAnnotated> annotated,
            String annotationFqn,
            String ownerKey,
            String enclosingClass,
            KspModel model) {
        return named(annotated, annotationFqn, ownerKey, enclosingClass, model).stream()
                .findFirst();
    }

    /**
     * Direct occurrences plus those inside the annotation's {@code @Repeatable} container, in
     * source order, as {@code AnnotatedElement.getAnnotationsByType} reads them. Kotlin repeatable
     * annotations already appear one by one.
     */
    static List<AnnotationRef> byType(
            KspRef<? extends KSAnnotated> annotated,
            String annotationFqn,
            String ownerKey,
            String enclosingClass,
            KspModel model) {
        var container = model.findJavaClass(annotationFqn)
                .flatMap(type -> type.annotation(REPEATABLE))
                .flatMap(repeatable -> repeatable.type(VALUE))
                .map(TypeRef::erasedName);
        var found = new ArrayList<AnnotationRef>();
        for (var annotation : all(annotated, ownerKey, enclosingClass, model)) {
            if (annotation.annotationName().equals(annotationFqn)) {
                found.add(annotation);
            } else if (container.isPresent() && annotation.annotationName().equals(container.get())) {
                contained(annotation, annotationFqn).forEach(found::add);
            }
        }
        return List.copyOf(found);
    }

    /** The annotations of the type a container holds in its {@code value} member. */
    private static List<AnnotationRef> contained(AnnotationRef container, String annotationFqn) {
        var items = container.value(VALUE).orElse(List.of());
        var found = new ArrayList<AnnotationRef>();
        if (items instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof AnnotationRef nested
                        && nested.annotationName().equals(annotationFqn)) {
                    found.add(nested);
                }
            }
        }
        return found;
    }

    /**
     * The annotations of one type. Each annotation is matched by the type it resolves to, not by
     * the short name it was written with: an import alias ({@code import ...Component as Comp})
     * changes the written name only (#706).
     */
    private static List<AnnotationRef> named(
            KspRef<? extends KSAnnotated> annotated,
            String annotationFqn,
            String ownerKey,
            String enclosingClass,
            KspModel model) {
        var annotations = SequencesKt.toList(annotated.get().getAnnotations());
        var refs = new ArrayList<AnnotationRef>();
        for (var i = 0; i < annotations.size(); i++) {
            var annotation = annotations.get(i);
            if (annotationName(annotation, model).equals(annotationFqn)) {
                refs.add(at(annotated, i, annotation, ownerKey, enclosingClass, model));
            }
        }
        return refs;
    }

    private static AnnotationRef at(
            KspRef<? extends KSAnnotated> annotated,
            int index,
            KSAnnotation annotation,
            String ownerKey,
            String enclosingClass,
            KspModel model) {
        var node = annotated.derive(
                owner -> SequencesKt.toList(owner.getAnnotations()).get(index));
        return new KspAnnotationRef(
                node, annotationName(annotation, model), ownerKey + "@" + index, enclosingClass, model);
    }

    /** The Java name of the annotation type, or the name it was written as when it does not resolve. */
    private static String annotationName(KSAnnotation annotation, KspModel model) {
        var type = annotation.getAnnotationType().resolve();
        if (type.isError() || type.getDeclaration().getQualifiedName() == null) {
            return KspModel.errorName(type.getDeclaration());
        }
        return model.javaName(type.getDeclaration().getQualifiedName().asString());
    }

    @Override
    public String annotationName() {
        return annotationName;
    }

    @Override
    public Optional<Object> value(String member) {
        var current = values();
        return current.members.containsKey(member)
                ? Optional.ofNullable(current.members.get(member))
                : Optional.empty();
    }

    @Override
    public boolean isUnresolved(String member) {
        var current = values();
        if (!current.members.containsKey(member)) {
            throw new NoSuchElementException("@" + annotationName + " has no member '" + member + "'");
        }
        return current.unresolved.contains(member);
    }

    @Override
    public Location location() {
        return new KspLocation(node, enclosingClass);
    }

    /** The member values, read once per round. */
    private Values values() {
        if (values == null || values.round != model.session().round()) {
            values = read(node.get());
        }
        return values;
    }

    private Values read(KSAnnotation annotation) {
        var read = new Values(model.session().round());
        for (var argument : annotation.getArguments()) {
            var member = argument.getName() == null ? VALUE : argument.getName().asString();
            var converted = convert(argument.getValue(), member, read);
            read.members.put(member, converted == UNRESOLVED ? List.of() : converted);
        }
        return read;
    }

    private static final Object UNRESOLVED = new Object();

    private Object convert(Object raw, String member, Values read) {
        return switch (raw) {
            case null -> null;
            case KSType type when type.isError() -> unresolved(member, read);
            case KSType type
            when type.getDeclaration() instanceof KSClassDeclaration enumEntry
                    && enumEntry.getClassKind() == ClassKind.ENUM_ENTRY ->
                enumEntry.getSimpleName().asString();
            case KSType type -> classLiteral(type);
            case KSClassDeclaration enumEntry -> enumEntry.getSimpleName().asString();
            case KSAnnotation nested -> nested(member, -1, nested);
            case List<?> items -> list(items, member, read);
            default -> raw;
        };
    }

    private List<Object> list(List<?> items, String member, Values read) {
        var converted = new ArrayList<Object>();
        for (var i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var value = item instanceof KSAnnotation nested ? nested(member, i, nested) : convert(item, member, read);
            if (value != UNRESOLVED) {
                converted.add(value);
            }
        }
        return List.copyOf(converted);
    }

    private static Object unresolved(String member, Values read) {
        read.unresolved.add(member);
        return UNRESOLVED;
    }

    /** {@code Foo::class}: the raw class type, found again by class name in later rounds. */
    private TypeRef classLiteral(KSType type) {
        var declaration = type.getDeclaration();
        if (declaration.getQualifiedName() == null) {
            return model.typeRef(model.ref(type, resolver -> type));
        }
        var kotlinName = declaration.getQualifiedName().asString();
        var ref = model.ref(
                type,
                resolver -> KspModel.findClass(resolver, kotlinName)
                        .asStarProjectedType()
                        .makeNotNullable());
        return new KspTypeRef(ref, Variance.INVARIANT, false, true, false, model);
    }

    /** A nested annotation, found again through this one by member and position. */
    private AnnotationRef nested(String member, int index, KSAnnotation nested) {
        var nestedNode = node.derive(owner -> nestedAt(owner, member, index));
        var nestedKey = key + "/" + member + (index < 0 ? "" : "[" + index + "]");
        return new KspAnnotationRef(nestedNode, annotationName(nested, model), nestedKey, enclosingClass, model);
    }

    private static KSAnnotation nestedAt(KSAnnotation owner, String member, int index) {
        for (var argument : owner.getArguments()) {
            var name = argument.getName() == null ? VALUE : argument.getName().asString();
            if (name.equals(member)) {
                var value = argument.getValue();
                return (KSAnnotation) (index < 0 ? value : ((List<?>) value).get(index));
            }
        }
        throw new NoSuchElementException("No member '" + member + "'");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KspAnnotationRef that
                && key.equals(that.key)
                && annotationName.equals(that.annotationName);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "@" + annotationName + " on " + key;
    }

    /** One round's reading of the member values. */
    private static final class Values {
        final int round;
        final Map<String, Object> members = new LinkedHashMap<>();
        final Set<String> unresolved = new HashSet<>();

        Values(int round) {
            this.round = round;
        }
    }
}
