package io.tiko.processor.core.javac;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Which javac elements and annotation mirrors are the same declaration, whichever round made
 * them. javac keeps one symbol per class for the whole compilation, but enters the members of a
 * source class, and the annotations on everything, afresh every round: the same method mapped in
 * two rounds wraps two elements. So a member is identified by where it is declared, as the KSP
 * adapter's keys do: by its class (or, for a parameter, its method), kind and name, and a method
 * or constructor also by its erased parameter types, which no two overloads share. An annotation
 * is identified by its declaration and its place there: its position among the declaration's
 * annotations, then member name and array position down to a nested one.
 */
final class JavacIdentity {

    /** The kinds identified by where they are declared; every other element is its own identity. */
    private static final Set<ElementKind> MEMBERS = Set.of(
            ElementKind.METHOD,
            ElementKind.CONSTRUCTOR,
            ElementKind.FIELD,
            ElementKind.ENUM_CONSTANT,
            ElementKind.RECORD_COMPONENT,
            ElementKind.PARAMETER);

    private JavacIdentity() {}

    /** Whether the two elements are the same declaration. */
    static boolean same(Element a, Element b, Types types) {
        if (a == b || a.equals(b)) {
            return true;
        }
        if (a.getKind() != b.getKind()
                || !MEMBERS.contains(a.getKind())
                || !a.getSimpleName().contentEquals(b.getSimpleName())
                || !same(a.getEnclosingElement(), b.getEnclosingElement(), types)) {
            return false;
        }
        return !(a instanceof ExecutableElement method && b instanceof ExecutableElement other)
                || sameErasedParameters(method, other, types);
    }

    private static boolean sameErasedParameters(ExecutableElement a, ExecutableElement b, Types types) {
        var params = a.getParameters();
        var others = b.getParameters();
        if (params.size() != others.size()) {
            return false;
        }
        for (var i = 0; i < params.size(); i++) {
            var erased = types.erasure(params.get(i).asType()).toString();
            if (!erased.equals(types.erasure(others.get(i).asType()).toString())) {
                return false;
            }
        }
        return true;
    }

    /** A hash code consistent with {@link #same}. */
    static int hash(Element element) {
        if (!MEMBERS.contains(element.getKind())) {
            return element.hashCode();
        }
        return 31 * hash(element.getEnclosingElement())
                + element.getSimpleName().toString().hashCode();
    }

    /**
     * This round's element for the same declaration; {@code element} itself when it is current,
     * or when no current element matches it.
     */
    static Element current(Element element, Types types) {
        if (!MEMBERS.contains(element.getKind())) {
            return element;
        }
        var enclosing = current(element.getEnclosingElement(), types);
        List<? extends Element> candidates =
                element.getKind() == ElementKind.PARAMETER && enclosing instanceof ExecutableElement method
                        ? method.getParameters()
                        : enclosing.getEnclosedElements();
        for (var candidate : candidates) {
            if (same(element, candidate, types)) {
                return candidate;
            }
        }
        return element;
    }

    /**
     * The mirror at {@code path} on {@code annotated}: its position among the element's
     * annotations, then {@code /member} or {@code /member[index]} per nesting level (see
     * {@link JavacAnnotationRef}). Empty when there is none.
     */
    static Optional<AnnotationMirror> at(Element annotated, String path, Elements elements) {
        var steps = path.split("/");
        var mirrors = annotated.getAnnotationMirrors();
        var index = Integer.parseInt(steps[0]);
        if (index >= mirrors.size()) {
            return Optional.empty();
        }
        AnnotationMirror cursor = mirrors.get(index);
        for (var i = 1; i < steps.length && cursor != null; i++) {
            cursor = step(cursor, steps[i], elements);
        }
        return Optional.ofNullable(cursor);
    }

    /** The nested annotation that {@code member} or {@code member[index]} names in {@code owner}. */
    private static AnnotationMirror step(AnnotationMirror owner, String step, Elements elements) {
        var bracket = step.indexOf('[');
        var member = bracket < 0 ? step : step.substring(0, bracket);
        var index = bracket < 0 ? -1 : Integer.parseInt(step.substring(bracket + 1, step.length() - 1));
        for (var entry : elements.getElementValuesWithDefaults(owner).entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(member)) {
                var value = entry.getValue().getValue();
                if (index >= 0 && value instanceof List<?> items && index < items.size()) {
                    value = ((AnnotationValue) items.get(index)).getValue();
                }
                return value instanceof AnnotationMirror nested ? nested : null;
            }
        }
        return null;
    }
}
