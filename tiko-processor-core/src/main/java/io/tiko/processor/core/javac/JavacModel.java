package io.tiko.processor.core.javac;

import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.SourceLanguage;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.core.model.Visibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;

/**
 * The {@code Element} / {@code TypeMirror} to neutral-model mapping, plus the helpers the javac
 * model records share. One instance per {@link JavacProcessingEnv}; model equality never depends
 * on which one made a model object (see {@link JavacIdentity}).
 */
record JavacModel(Elements elements, Types types) {

    private static final String REPEATABLE = "java.lang.annotation.Repeatable";

    ClassDecl classDecl(TypeElement element) {
        return new JavacClassDecl(element, this);
    }

    MethodDecl methodDecl(ExecutableElement element) {
        return new JavacMethodDecl(element, this);
    }

    TypeRef typeRef(TypeMirror mirror) {
        return new JavacTypeRef(mirror, this);
    }

    /** The annotation at {@code path} on {@code annotated} (see {@link JavacAnnotationRef}). */
    AnnotationRef annotationRef(AnnotationMirror mirror, Element annotated, String path) {
        return new JavacAnnotationRef(mirror, annotated, path, this);
    }

    List<AnnotationRef> annotations(Element element) {
        var mirrors = element.getAnnotationMirrors();
        var refs = new ArrayList<AnnotationRef>();
        for (var i = 0; i < mirrors.size(); i++) {
            refs.add(annotationRef(mirrors.get(i), element, String.valueOf(i)));
        }
        return List.copyOf(refs);
    }

    Optional<AnnotationRef> annotation(Element element, String annotationFqn) {
        var mirrors = element.getAnnotationMirrors();
        for (var i = 0; i < mirrors.size(); i++) {
            if (annotationName(mirrors.get(i)).equals(annotationFqn)) {
                return Optional.of(annotationRef(mirrors.get(i), element, String.valueOf(i)));
            }
        }
        return Optional.empty();
    }

    /**
     * Direct occurrences plus those inside the annotation's {@code @Repeatable} container, in the
     * order the element carries them, as {@code AnnotatedElement.getAnnotationsByType} reads them.
     */
    List<AnnotationRef> annotationsByType(Element element, String annotationFqn) {
        var container = repeatableContainer(annotationFqn);
        var found = new ArrayList<AnnotationRef>();
        var mirrors = element.getAnnotationMirrors();
        for (var i = 0; i < mirrors.size(); i++) {
            var mirror = mirrors.get(i);
            var name = annotationName(mirror);
            if (name.equals(annotationFqn)) {
                found.add(annotationRef(mirror, element, String.valueOf(i)));
            } else if (container.isPresent() && name.equals(container.get())) {
                var items = contained(mirror);
                for (var j = 0; j < items.size(); j++) {
                    if (items.get(j) instanceof AnnotationMirror item
                            && annotationName(item).equals(annotationFqn)) {
                        found.add(annotationRef(item, element, i + "/value[" + j + "]"));
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    /** The container named by the annotation type's {@code @Repeatable}; empty when it has none. */
    private Optional<String> repeatableContainer(String annotationFqn) {
        var annotationType = elements.getTypeElement(annotationFqn);
        if (annotationType == null) {
            return Optional.empty();
        }
        return annotation(annotationType, REPEATABLE)
                .flatMap(repeatable -> repeatable.type("value"))
                .map(TypeRef::erasedName);
    }

    /** The values a container holds in its {@code value} member, by position in the array. */
    private List<Object> contained(AnnotationMirror container) {
        var items = new ArrayList<>();
        elements.getElementValuesWithDefaults(container).forEach((member, value) -> {
            if (member.getSimpleName().contentEquals("value") && value.getValue() instanceof List<?> values) {
                values.stream().map(item -> ((AnnotationValue) item).getValue()).forEach(items::add);
            }
        });
        return items;
    }

    static String annotationName(AnnotationMirror mirror) {
        return ((TypeElement) mirror.getAnnotationType().asElement())
                .getQualifiedName()
                .toString();
    }

    static Visibility visibility(Set<Modifier> modifiers) {
        if (modifiers.contains(Modifier.PUBLIC)) {
            return Visibility.PUBLIC;
        }
        if (modifiers.contains(Modifier.PROTECTED)) {
            return Visibility.PROTECTED;
        }
        if (modifiers.contains(Modifier.PRIVATE)) {
            return Visibility.PRIVATE;
        }
        return Visibility.PACKAGE;
    }

    SourceLanguage language(TypeElement element) {
        try {
            var file = elements.getFileObjectOf(element);
            return file instanceof JavaFileObject source && source.getKind() == JavaFileObject.Kind.SOURCE
                    ? SourceLanguage.JAVA
                    : SourceLanguage.CLASSPATH;
        } catch (UnsupportedOperationException e) {
            // An Elements implementation without file lookup (not javac): treat the type as source.
            return SourceLanguage.JAVA;
        }
    }

    /** The URI path of the element's file object; empty when javac reports none. */
    Optional<String> sourcePath(TypeElement element) {
        try {
            return Optional.ofNullable(elements.getFileObjectOf(element))
                    .map(file -> file.toUri().getPath());
        } catch (UnsupportedOperationException e) {
            // An Elements implementation without file lookup (not javac).
            return Optional.empty();
        }
    }

    /** The javac mirror behind a model type. */
    static TypeMirror mirror(TypeRef type) {
        if (type instanceof JavacTypeRef javac) {
            return javac.mirror();
        }
        throw new IllegalArgumentException("Not a javac type: " + type);
    }

    /** The javac element behind a model class. */
    static TypeElement element(ClassDecl decl) {
        if (decl instanceof JavacClassDecl javac) {
            return javac.element();
        }
        throw new IllegalArgumentException("Not a javac class: " + decl);
    }
}
