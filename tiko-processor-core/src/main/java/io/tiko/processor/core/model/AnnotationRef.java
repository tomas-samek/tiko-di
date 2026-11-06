package io.tiko.processor.core.model;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * An annotation on a declaration. Member values include the annotation's declared defaults.
 *
 * <p>{@link #value(String)} returns {@code String}, {@code Boolean}, {@code Integer}, {@code Long}
 * (and the other primitive boxes) as-is, an enum constant as its name {@code String}, a
 * {@code Class} value as {@link TypeRef}, an array as {@code List<Object>} and a nested
 * annotation as {@code AnnotationRef}. The typed accessors cast that value and throw
 * {@link NoSuchElementException} when the annotation has no such member.
 *
 * <p>A {@code Class} value whose type does not resolve (on javac an erroneous class literal, read
 * as {@code "<error>"} or as a type of kind {@code ERROR}; for example a class another processor
 * has yet to generate) is left out: a single such value reads as
 * an empty list, and an array omits it. {@link #types(String)} and {@link #type(String)} therefore
 * never see it; the compiler reports the unresolved symbol itself.
 *
 * <p>Two instances are equal when they denote the same annotation on the same declaration, even
 * when different {@code ProcessingEnv} instances (rounds) produced them; {@code hashCode} follows.
 */
public interface AnnotationRef {

    /** Fully qualified name of the annotation type. */
    String annotationName();

    /** The member's value, or empty when the annotation type declares no such member. */
    Optional<Object> value(String member);

    default String string(String member) {
        return (String) required(member);
    }

    default boolean bool(String member) {
        return (Boolean) required(member);
    }

    default int integer(String member) {
        return (Integer) required(member);
    }

    /** {@code Class} values; a single (non-array) value comes back as a one-element list. */
    default List<TypeRef> types(String member) {
        return listOf(member, TypeRef.class);
    }

    /** A single {@code Class} value; empty when its type does not resolve. */
    default Optional<TypeRef> type(String member) {
        return types(member).stream().findFirst();
    }

    /** {@code String} values; a single (non-array) value comes back as a one-element list. */
    default List<String> strings(String member) {
        return listOf(member, String.class);
    }

    default String enumName(String member) {
        return (String) required(member);
    }

    Location location();

    private Object required(String member) {
        return value(member)
                .orElseThrow(
                        () -> new NoSuchElementException("@" + annotationName() + " has no member '" + member + "'"));
    }

    private <T> List<T> listOf(String member, Class<T> elementType) {
        var raw = required(member);
        var items = raw instanceof List<?> list ? list : List.of(raw);
        return items.stream().map(elementType::cast).toList();
    }
}
