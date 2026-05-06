package io.tiko.processor.config;

import io.tiko.processor.util.ProcessorContext;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** All compile-time checks against the collected {@link ConfigurationModel}s. */
public final class ConfigurationValidator {

    private final ProcessorContext ctx;
    private final Types types;

    public ConfigurationValidator(ProcessorContext ctx, Types types) {
        this.ctx = ctx;
        this.types = types;
    }

    public boolean validate() {
        boolean ok = true;
        ok &= checkPrefixUniqueness();
        for (ConfigurationModel cfg : ctx.getConfigurations()) {
            ok &= checkFields(cfg);
            ok &= checkNoRecursion(cfg);
        }
        return ok;
    }

    private boolean checkPrefixUniqueness() {
        Map<String, ConfigurationModel> seen = new HashMap<>();
        boolean ok = true;
        for (ConfigurationModel cfg : ctx.getConfigurations()) {
            ConfigurationModel prior = seen.put(cfg.prefix(), cfg);
            if (prior != null) {
                ctx.getErrorReporter().error(cfg.element(),
                    prior.simpleName() + ".java, " + cfg.simpleName()
                        + ".java — Both records declare prefix '" + cfg.prefix() + "'."
                        + " Each prefix must be unique.",
                    "Rename one of the prefixes");
                ok = false;
            }
        }
        return ok;
    }

    private boolean checkFields(ConfigurationModel cfg) {
        boolean ok = true;
        for (ConfigFieldModel f : cfg.fields()) {
            // 1. Type-set membership
            TypeMirror inner = unwrapOptional(f.type());
            if (!ConfigSupportedTypes.isSupported(inner, types)) {
                String typeName = simpleName(inner);
                ctx.getErrorReporter().error(f.element(),
                    "Field '" + f.fieldName() + "' uses unsupported config type '" + typeName + "'."
                        + " See *Components → Supported types* in the spec.",
                    "Use one of: " + String.join(", ", ConfigSupportedTypes.bundledTypeNames()),
                    "Or declare as String and parse in your service");
                ok = false;
            }

            // 2. @Default + Optional<X> conflict
            if (f.cardinality() == ConfigFieldModel.Cardinality.OPTIONAL && f.defaultValue() != null) {
                ctx.getErrorReporter().error(f.element(),
                    "@Default cannot be combined with Optional<X> on field '" + f.fieldName() + "'."
                        + " They mean different things.",
                    "Drop the Optional wrapper, or remove @Default");
                ok = false;
            }

            // 3. @Default value parseability for the effective scalar type
            if (f.cardinality() == ConfigFieldModel.Cardinality.DEFAULTED) {
                Class<?> effective = effectiveClass(inner);
                if (effective != null) {
                    String err = ConfigSupportedTypes.validateDefault(f.defaultValue(), effective);
                    if (err != null) {
                        ctx.getErrorReporter().error(f.element(),
                            "@Default('" + f.defaultValue() + "') on " + simpleName(inner)
                                + " field '" + f.fieldName() + "' is not a valid " + effective.getSimpleName() + ": " + err,
                            "Provide a value parseable as " + effective.getSimpleName());
                        ok = false;
                    }
                }
            }
        }
        return ok;
    }

    private boolean checkNoRecursion(ConfigurationModel cfg) {
        Set<String> visiting = new HashSet<>();
        return walk(cfg.element(), visiting, cfg);
    }

    private boolean walk(TypeElement type, Set<String> visiting, ConfigurationModel root) {
        String fqn = type.getQualifiedName().toString();
        if (!visiting.add(fqn)) {
            ctx.getErrorReporter().error(root.element(),
                "Recursive record reference detected at " + fqn);
            return false;
        }
        for (var member : type.getEnclosedElements()) {
            if (!member.getKind().name().equals("RECORD_COMPONENT")) continue;
            TypeMirror t = unwrapOptional(member.asType());
            if (t.getKind() == TypeKind.DECLARED) {
                TypeElement child = (TypeElement) ((DeclaredType) t).asElement();
                if (child.getKind() == ElementKind.RECORD) {
                    if (!walk(child, visiting, root)) return false;
                }
            }
        }
        visiting.remove(fqn);
        return true;
    }

    private TypeMirror unwrapOptional(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return type;
        DeclaredType dt = (DeclaredType) type;
        TypeElement el = (TypeElement) dt.asElement();
        if (!el.getQualifiedName().toString().equals("java.util.Optional")) return type;
        if (dt.getTypeArguments().isEmpty()) return type;
        return dt.getTypeArguments().get(0);
    }

    private static Class<?> effectiveClass(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return switch (type.getKind()) {
                case INT -> int.class;
                case LONG -> long.class;
                case BOOLEAN -> boolean.class;
                case DOUBLE -> double.class;
                case FLOAT -> float.class;
                case SHORT -> short.class;
                case BYTE -> byte.class;
                case CHAR -> char.class;
                default -> null;
            };
        }
        if (type.getKind() == TypeKind.DECLARED) {
            String fqn = ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
            try { return Class.forName(fqn); } catch (ClassNotFoundException e) { return null; }
        }
        return null;
    }

    private static String simpleName(TypeMirror type) {
        if (type.getKind().isPrimitive()) return type.getKind().name().toLowerCase();
        if (type.getKind() == TypeKind.DECLARED) {
            return ((TypeElement) ((DeclaredType) type).asElement()).getSimpleName().toString();
        }
        return type.toString();
    }
}
