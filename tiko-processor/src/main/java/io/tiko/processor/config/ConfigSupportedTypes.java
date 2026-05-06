// tiko-processor/src/main/java/io/tiko/processor/config/ConfigSupportedTypes.java
package io.tiko.processor.config;

import io.tiko.config.internal.coercers.CoercionException;
import io.tiko.config.internal.coercers.TypeCoercer;
import io.tiko.config.internal.coercers.TypeCoercerRegistry;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.List;

/**
 * Bridge between processor element types and the runtime coercer set.
 * Centralised so error messages, validation, and codegen all consult the same
 * source of truth for "is this type bindable?".
 */
public final class ConfigSupportedTypes {

    /**
     * Returns true if {@code type} is supported as a record-component type:
     * a bundled scalar, an enum, a {@code List<X>} / {@code Map<String,X>} where
     * X is itself supported (a scalar or a record), an {@code Optional<X>}, or
     * a record (nested binding).
     */
    public static boolean isSupported(TypeMirror type, Types types) {
        if (type.getKind().isPrimitive()) return primitiveSupported(type.getKind());
        if (type.getKind() != TypeKind.DECLARED) return false;
        TypeElement el = (TypeElement) types.asElement(type);
        if (el == null) return false;
        String fqn = el.getQualifiedName().toString();

        // Records (nested binding) — ConfigBinderGenerator emits a recursive call.
        if (el.getKind().name().equals("RECORD")) return true;

        if (el.getKind() == javax.lang.model.element.ElementKind.ENUM) return true;

        // Optional / List / Map are accepted; the generator inspects the type argument.
        if (fqn.equals("java.util.Optional") || fqn.equals("java.util.List") || fqn.equals("java.util.Map")) {
            return true; // element-type validation happens in ConfigurationValidator
        }

        // Bundled scalars: ask the runtime registry directly using Class.forName.
        try {
            Class<?> c = Class.forName(fqn);
            return TypeCoercerRegistry.isSupported(c);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean primitiveSupported(TypeKind kind) {
        return switch (kind) {
            case INT, LONG, BOOLEAN, DOUBLE, FLOAT, SHORT, BYTE, CHAR -> true;
            default -> false;
        };
    }

    /**
     * Validates a {@code @Default("…")} string against the field's declared (effective) type.
     * Returns a description of the failure, or {@code null} on success.
     */
    public static String validateDefault(String defaultValue, Class<?> effectiveType) {
        try {
            TypeCoercer<?> coercer = TypeCoercerRegistry.get(effectiveType);
            coercer.coerce(defaultValue);
            return null;
        } catch (CoercionException e) {
            return e.getMessage();
        } catch (IllegalArgumentException e) {
            return "no coercer for " + effectiveType.getName();
        }
    }

    public static List<String> bundledTypeNames() {
        return List.of(
            "primitives + boxed", "String", "Duration", "Instant", "LocalDate", "LocalDateTime",
            "ZoneId", "UUID", "URI", "Path", "BigDecimal", "Pattern", "Charset",
            "enums", "List<X>", "Map<String,X>", "nested records", "Optional<X>"
        );
    }
}
