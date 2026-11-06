package io.tiko.processor.config;

import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeRefs;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** All compile-time checks against the collected {@link ConfigurationModel}s. */
public final class ConfigurationValidator {

    private final ProcessorContext ctx;

    public ConfigurationValidator(ProcessorContext ctx) {
        this.ctx = ctx;
    }

    public boolean validate() {
        boolean ok = true;
        ok &= checkPrefixUniqueness();
        for (ConfigurationModel cfg : ctx.getConfigurations()) {
            ok &= checkPrefixIsOneLine(cfg);
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
                ctx.getErrorReporter()
                        .error(
                                cfg.declaration().location(),
                                prior.simpleName() + ".java, " + cfg.simpleName()
                                        + ".java — Both records declare prefix '" + cfg.prefix() + "'."
                                        + " Each prefix must be unique.",
                                "Rename one of the prefixes");
                ok = false;
            }
        }
        return ok;
    }

    /**
     * {@code META-INF/tiko/configs.txt} stores one {@code <fqn>=<prefix>} entry per line, and the
     * runtime loads each listed name with {@code Class.forName}. A line break, another control
     * character or {@code =} in the prefix could add or alter entries (SEC-5, #496).
     */
    private boolean checkPrefixIsOneLine(ConfigurationModel cfg) {
        boolean unsafe = cfg.prefix().chars().anyMatch(c -> c == '=' || Character.isISOControl(c));
        if (unsafe) {
            ctx.getErrorReporter()
                    .error(
                            cfg.declaration().location(),
                            cfg.simpleName() + ".java — the @Configuration prefix contains a line break,"
                                    + " another control character or '='.",
                            "Use a dot-separated path such as 'tiko.my-app'");
        }
        return !unsafe;
    }

    private boolean checkFields(ConfigurationModel cfg) {
        boolean ok = true;
        for (ConfigFieldModel f : cfg.fields()) {
            // 1. Type-set membership
            TypeRef inner = unwrapOptional(f.type());
            if (!ConfigSupportedTypes.isSupported(inner)) {
                String typeName = simpleName(inner);
                ctx.getErrorReporter()
                        .error(
                                f.component().location(),
                                "Field '" + f.fieldName() + "' uses unsupported config type '" + typeName + "'."
                                        + " See *Components → Supported types* in the spec.",
                                "Use one of: " + String.join(", ", ConfigSupportedTypes.bundledTypeNames()),
                                "Or declare as String and parse in your service");
                ok = false;
            }

            // 2. @Default + Optional<X> conflict
            if (f.cardinality() == ConfigFieldModel.Cardinality.OPTIONAL && f.defaultValue() != null) {
                ctx.getErrorReporter()
                        .error(
                                f.component().location(),
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
                        ctx.getErrorReporter()
                                .error(
                                        f.component().location(),
                                        "@Default('" + f.defaultValue() + "') on " + simpleName(inner) + " field '"
                                                + f.fieldName() + "' is not a valid " + effective.getSimpleName() + ": "
                                                + err,
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
        return walk(cfg.declaration(), visiting, cfg);
    }

    private boolean walk(ClassDecl type, Set<String> visiting, ConfigurationModel root) {
        String fqn = type.qualifiedName();
        if (!visiting.add(fqn)) {
            ctx.getErrorReporter()
                    .error(
                            root.declaration().location(),
                            "Recursive record reference detected at " + fqn,
                            "Break the cycle: a @Configuration record cannot reference itself, directly or transitively",
                            "Flatten the recursive component into a separate top-level @Configuration record");
            return false;
        }
        for (var member : type.recordComponents()) {
            var child = TypeRefs.declared(unwrapOptional(member.type()));
            if (child.isPresent() && child.get().kind() == DeclKind.RECORD && !walk(child.get(), visiting, root)) {
                return false;
            }
        }
        visiting.remove(fqn);
        return true;
    }

    private TypeRef unwrapOptional(TypeRef type) {
        if (!TypeRefs.isDeclared(type, "java.util.Optional")) return type;
        if (type.typeArguments().isEmpty()) return type;
        return type.typeArguments().get(0);
    }

    private static Class<?> effectiveClass(TypeRef type) {
        if (type.isPrimitive()) {
            return switch (type.erasedName()) {
                case "int" -> int.class;
                case "long" -> long.class;
                case "boolean" -> boolean.class;
                case "double" -> double.class;
                case "float" -> float.class;
                case "short" -> short.class;
                case "byte" -> byte.class;
                case "char" -> char.class;
                default -> null;
            };
        }
        var declared = TypeRefs.declared(type);
        if (declared.isPresent()) {
            try {
                return Class.forName(declared.get().qualifiedName());
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
        return null;
    }

    /** A primitive's keyword ({@code int}), a declared type's simple name, else its rendering. */
    private static String simpleName(TypeRef type) {
        if (type.isPrimitive()) return type.erasedName();
        return TypeRefs.declared(type)
                .map(decl -> decl.className().simpleName())
                .orElseGet(type::displayName);
    }
}
