package io.tiko.processor.topology;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Maps a {@link TypeMirror} (as collected from a {@code @Configuration} record
 * component) to a JSON Schema fragment string. Composes recursively for arrays,
 * sets, maps, and nested records.
 *
 * <p>Stays in lockstep with the runtime {@code ConfigSupportedTypes} — adding a
 * new accepted type means updating both.
 */
public final class JsonSchemaTypeMapper {

    public String mapType(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return switch (type.getKind()) {
                case BOOLEAN -> "{\"type\":\"boolean\"}";
                case INT, LONG, SHORT, BYTE -> "{\"type\":\"integer\"}";
                case FLOAT, DOUBLE -> "{\"type\":\"number\"}";
                case CHAR -> "{\"type\":\"string\"}";
                default -> "{\"type\":\"string\"}";
            };
        }
        if (type.getKind() == TypeKind.DECLARED) {
            return mapDeclared((DeclaredType) type);
        }
        return "{\"type\":\"string\"}";
    }

    private String mapDeclared(DeclaredType dt) {
        var el = (TypeElement) dt.asElement();
        var fqn = el.getQualifiedName().toString();

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
                if (el.getKind() == ElementKind.ENUM) {
                    yield enumSchema(el);
                }
                if (el.getKind() == ElementKind.RECORD) {
                    yield recordSchema(el);
                }
                yield "{\"type\":\"string\"}";
            }
        };
    }

    private String itemSchema(DeclaredType collection, int typeArgIndex) {
        var args = collection.getTypeArguments();
        if (args.size() <= typeArgIndex) {
            return "{\"type\":\"string\"}";
        }
        return mapType(args.get(typeArgIndex));
    }

    private String enumSchema(TypeElement el) {
        var sb = new StringBuilder("{\"type\":\"string\",\"enum\":[");
        var first = true;
        for (var member : el.getEnclosedElements()) {
            if (member.getKind() == ElementKind.ENUM_CONSTANT) {
                if (!first) sb.append(',');
                sb.append('"').append(member.getSimpleName()).append('"');
                first = false;
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private String recordSchema(TypeElement record) {
        var sb = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        var first = true;
        for (var member : record.getEnclosedElements()) {
            if (member.getKind() == ElementKind.RECORD_COMPONENT) {
                if (!first) sb.append(',');
                sb.append('"').append(member.getSimpleName()).append("\":");
                sb.append(mapType(member.asType()));
                first = false;
            }
        }
        sb.append("},\"additionalProperties\":false}");
        return sb.toString();
    }
}
