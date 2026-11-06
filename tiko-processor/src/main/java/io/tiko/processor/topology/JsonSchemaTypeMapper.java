package io.tiko.processor.topology;

import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.util.TypeRefs;

/**
 * Maps a {@link TypeRef} (as collected from a {@code @Configuration} record
 * component) to a JSON Schema fragment string. Composes recursively for arrays,
 * sets, maps, and nested records.
 *
 * <p>Stays in lockstep with the runtime {@code ConfigSupportedTypes} — adding a
 * new accepted type means updating both.
 */
public final class JsonSchemaTypeMapper {

    public String mapType(TypeRef type) {
        if (type.isPrimitive()) {
            return switch (type.erasedName()) {
                case "boolean" -> "{\"type\":\"boolean\"}";
                case "int", "long", "short", "byte" -> "{\"type\":\"integer\"}";
                case "float", "double" -> "{\"type\":\"number\"}";
                case "char" -> "{\"type\":\"string\"}";
                default -> "{\"type\":\"string\"}";
            };
        }
        var declared = TypeRefs.declared(type);
        if (declared.isPresent()) {
            return mapDeclared(type, declared.get());
        }
        return "{\"type\":\"string\"}";
    }

    private String mapDeclared(TypeRef dt, ClassDecl el) {
        var fqn = el.qualifiedName();

        return switch (fqn) {
            case "java.lang.String", "java.lang.Character" -> "{\"type\":\"string\"}";
            case "java.lang.Boolean" -> "{\"type\":\"boolean\"}";
            case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte" -> "{\"type\":\"integer\"}";
            case "java.lang.Float", "java.lang.Double" -> "{\"type\":\"number\"}";
            case "java.time.Duration" -> "{\"type\":\"string\",\"format\":\"duration\"}";
            case "java.time.Instant",
                    "java.time.OffsetDateTime",
                    "java.time.ZonedDateTime",
                    "java.time.LocalDateTime" -> "{\"type\":\"string\",\"format\":\"date-time\"}";
            case "java.time.LocalDate" -> "{\"type\":\"string\",\"format\":\"date\"}";
            case "java.time.LocalTime" -> "{\"type\":\"string\",\"format\":\"time\"}";
            case "java.time.ZoneId" -> "{\"type\":\"string\"}";
            case "java.net.URI", "java.net.URL" -> "{\"type\":\"string\",\"format\":\"uri\"}";
            case "java.util.UUID",
                    "java.math.BigDecimal",
                    "java.util.regex.Pattern",
                    "java.nio.charset.Charset",
                    "java.nio.file.Path" -> "{\"type\":\"string\"}";
            case "java.util.List", "java.util.Set" -> "{\"type\":\"array\",\"items\":" + itemSchema(dt, 0) + "}";
            case "java.util.Map" -> "{\"type\":\"object\",\"additionalProperties\":" + itemSchema(dt, 1) + "}";
            case "java.util.Optional" -> itemSchema(dt, 0);
            default -> {
                if (el.kind() == DeclKind.ENUM) {
                    yield enumSchema(el);
                }
                if (el.kind() == DeclKind.RECORD) {
                    yield recordSchema(el);
                }
                yield "{\"type\":\"string\"}";
            }
        };
    }

    private String itemSchema(TypeRef collection, int typeArgIndex) {
        var args = collection.typeArguments();
        if (args.size() <= typeArgIndex) {
            return "{\"type\":\"string\"}";
        }
        return mapType(args.get(typeArgIndex));
    }

    private String enumSchema(ClassDecl el) {
        var sb = new StringBuilder("{\"type\":\"string\",\"enum\":[");
        var first = true;
        for (var constant : el.enumConstants()) {
            if (!first) sb.append(',');
            sb.append('"').append(constant).append('"');
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String recordSchema(ClassDecl record) {
        var sb = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        var first = true;
        for (var member : record.recordComponents()) {
            if (!first) sb.append(',');
            sb.append('"').append(member.name()).append("\":");
            sb.append(mapType(member.type()));
            first = false;
        }
        sb.append("},\"additionalProperties\":false}");
        return sb.toString();
    }
}
