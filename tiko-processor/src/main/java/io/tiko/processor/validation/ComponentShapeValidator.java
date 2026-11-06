package io.tiko.processor.validation;

import io.tiko.annotations.Inject;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.Visibility;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.EventHandlerModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Structural checks that must pass before any other validator or generator touches the
 * collected models (#333). Each case here used to surface as a raw javac error inside
 * generated sources or as a processor stack trace:
 *
 * <ul>
 *   <li>raw {@code Provider} / {@code Picker} injection points (no type argument) — the
 *       dependency key derefs the missing argument and NPEs the processor;</li>
 *   <li>abstract or non-static nested {@code @Component} classes — the generated factory
 *       cannot instantiate them;</li>
 *   <li>two components sharing a simple name — per-simple-name factories and getters
 *       collide ({@code FilerException});</li>
 *   <li>{@code @EventHandler} methods on classes that are not components — the generated
 *       registry references a getter that does not exist.</li>
 * </ul>
 *
 * <p>The caller short-circuits on failure: downstream validators assume well-shaped
 * models, so running them against a malformed one reintroduces the crashes this
 * validator exists to prevent.
 */
public final class ComponentShapeValidator {

    private static final String INJECT = Inject.class.getCanonicalName();

    private final ProcessorContext context;
    private final TypeUtil typeUtil;

    public ComponentShapeValidator(ProcessorContext context, TypeUtil typeUtil) {
        this.context = context;
        this.typeUtil = typeUtil;
    }

    public boolean validate() {
        boolean valid = true;

        Map<String, List<ComponentModel>> bySimpleName = new LinkedHashMap<>();
        for (ComponentModel component : context.getComponents().values()) {
            valid &= validateInstantiable(component);
            valid &= validatePublicMembers(component);
            valid &= validateDependencies(component.getDecl().location(), component.getDependencies());
            bySimpleName
                    .computeIfAbsent(component.getClassName(), k -> new java.util.ArrayList<>())
                    .add(component);
        }
        for (FactoryMethodModel factory : context.getFactoryMethods()) {
            valid &= validateDependencies(factory.getMethod().location(), factory.getDependencies());
        }

        for (Map.Entry<String, List<ComponentModel>> entry : bySimpleName.entrySet()) {
            List<ComponentModel> sharing = entry.getValue();
            if (sharing.size() <= 1) continue;
            String fqns = sharing.stream().map(ComponentModel::getQualifiedName).collect(Collectors.joining(", "));
            context.getErrorReporter()
                    .duplicateSimpleNames(sharing.get(0).getDecl().location(), entry.getKey(), fqns);
            valid = false;
        }

        for (EventHandlerModel handler : context.getEventHandlers()) {
            String declaringFqn = handler.getOwner().qualifiedName();
            boolean onComponent = context.getComponents().values().stream()
                    .anyMatch(c -> c.getQualifiedName().equals(declaringFqn));
            if (!onComponent) {
                context.getErrorReporter()
                        .eventHandlerOutsideComponent(
                                handler.getMethod().location(),
                                handler.getOwner().className().simpleName());
                valid = false;
            } else {
                valid &= requirePublic(handler.getMethod(), "@EventHandler");
            }
        }

        return valid;
    }

    /**
     * The generated factory, container and registry call these members from the
     * {@code io.tiko.generated} package, so a non-public one surfaced as a javac access error in
     * generated code (#464). A component built by a static {@code @Produces} factory may keep a
     * private constructor; only an {@code @Inject} constructor is invoked directly.
     */
    private boolean validatePublicMembers(ComponentModel component) {
        boolean valid = true;
        MethodDecl constructor = component.getConstructor();
        if (constructor != null
                && component.getStaticFactoryMethod().isEmpty()
                && constructor.annotation(INJECT).isPresent()) {
            valid &= requirePublic(constructor, "@Inject");
        }
        for (MethodDecl hook : component.getPostConstructMethods()) {
            valid &= requirePublic(hook, "@PostConstruct");
        }
        for (MethodDecl hook : component.getPreDestroyMethods()) {
            valid &= requirePublic(hook, "@PreDestroy");
        }
        return valid;
    }

    private boolean requirePublic(MethodDecl member, String annotation) {
        if (member.visibility() == Visibility.PUBLIC) return true;
        String owner = member.owner().orElseThrow().className().simpleName();
        boolean constructor = member.isConstructor();
        String name = constructor ? owner : member.name();
        reportNotPublic(member.location(), annotation, owner, name, constructor);
        return false;
    }

    private void reportNotPublic(Location at, String annotation, String owner, String name, boolean constructor) {
        String kind = constructor ? "constructor" : "method";
        context.getErrorReporter()
                .error(
                        at,
                        annotation + " " + kind + " '" + owner + (constructor ? "" : "." + name) + "' must be public;"
                                + " the generated code calls it from the io.tiko.generated package.",
                        "Add the public modifier to " + (constructor ? "the constructor" : "'" + name + "'"));
    }

    private boolean validateInstantiable(ComponentModel component) {
        ClassDecl el = component.getDecl();
        if (!typeUtil.isConcreteClass(el)) {
            context.getErrorReporter()
                    .nonInstantiableComponent(
                            el.location(),
                            component.getClassName(),
                            "it is not a concrete class",
                            "Make " + component.getClassName() + " a concrete (non-abstract) class",
                            "Remove the @Component annotation");
            return false;
        }
        // Collected components are top-level or member classes (never local), so "nested" is
        // "has an enclosing class".
        if (el.enclosingClass().isPresent() && !el.isStatic()) {
            context.getErrorReporter()
                    .nonInstantiableComponent(
                            el.location(),
                            component.getClassName(),
                            "non-static nested classes need an enclosing instance the container cannot provide",
                            "Declare " + component.getClassName() + " as a static nested class",
                            "Move " + component.getClassName() + " to the top level");
            return false;
        }
        return true;
    }

    private boolean validateDependencies(Location owner, List<DependencyModel> dependencies) {
        boolean valid = true;
        for (DependencyModel dep : dependencies) {
            boolean rawWrapper = (dep.isProvider() || dep.isPicker())
                    && dep.getUnwrappedType().isEmpty();
            if (rawWrapper) {
                String wrapper = dep.isProvider() ? "Provider" : "Picker";
                context.getErrorReporter()
                        .rawWrapperInjection(dep.getParameter().location(), owner, wrapper);
                valid = false;
            }
        }
        return valid;
    }
}
