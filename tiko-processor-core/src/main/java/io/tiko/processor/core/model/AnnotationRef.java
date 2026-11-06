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
 * as {@code "<error>"} or as a type of kind {@code ERROR}) reads as absent: a single such value
 * reads as an empty list, and an array omits it, so {@link #types(String)} and
 * {@link #type(String)} never see it. The compiler does not always report the symbol itself: a
 * class another processor generates in a later round is unresolved while this round runs and
 * compiles cleanly afterwards. A caller for which a missing value changes the meaning (a dropped
 * guard, for example) must check {@link #isUnresolved(String)}.
 *
 * <p>Equality is occurrence identity, across rounds: two instances are equal exactly when they
 * denote the same occurrence of an annotation on the same declaration, whichever
 * {@code ProcessingEnv} produced them, of the same round or of different rounds; {@code hashCode}
 * follows. An occurrence is its position among the declaration's annotations and, for an
 * annotation nested in another's member (a {@code @Repeatable} container's included), that
 * member and its position in the array: two identical repeated annotations are two occurrences.
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

    /**
     * Whether any {@code Class} value of the member (the single value, or any array element) did
     * not resolve, and so reads as absent from {@link #types(String)} and {@link #type(String)};
     * {@code false} for a member that is not {@code Class}-valued.
     *
     * @throws NoSuchElementException when the annotation has no such member
     */
    boolean isUnresolved(String member);

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
