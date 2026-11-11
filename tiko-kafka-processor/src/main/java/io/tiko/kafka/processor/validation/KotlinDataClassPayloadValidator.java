package io.tiko.kafka.processor.validation;

import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import io.tiko.processor.core.Diagnostics;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.TypeRef;
import java.util.List;

/**
 * Rejects a Kotlin {@code data class} without {@code @JvmRecord} as a payload the default JSON
 * serializer has to build: its only constructor takes parameters Jackson can't name, so every
 * record fails to decode at runtime (#649). The rule applies only while the bridge leaves
 * {@code serializer} at its {@code KafkaSerializer.Default} marker; an explicit serializer class
 * is the user's own and may handle the class. Java output never trips it: javac reports no data class.
 */
public final class KotlinDataClassPayloadValidator {

    private static final String DEFAULT_SERIALIZER = "io.tiko.kafka.KafkaSerializer.Default";

    private KotlinDataClassPayloadValidator() {}

    public static boolean validate(
            Diagnostics diagnostics, List<KafkaSourceDescriptor> sources, List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (var source : sources) {
            ok &= check(
                    diagnostics,
                    "@KafkaSource",
                    source.method().location(),
                    source.serializerClass(),
                    source.payloadType());
        }
        for (var sink : sinks) {
            ok &= check(
                    diagnostics,
                    "@KafkaSink",
                    sink.method().location(),
                    sink.serializerClass(),
                    sink.producedPayloadType());
        }
        return ok;
    }

    private static boolean check(
            Diagnostics diagnostics, String annotation, Location location, TypeRef serializer, TypeRef payload) {
        if (payload == null || !isDefault(serializer)) return true;
        var declaration = payload.declaration();
        if (declaration.isEmpty()
                || !declaration.get().isKotlinDataClass()
                || declaration.get().kind() == DeclKind.RECORD) {
            return true;
        }
        var name = payload.displayName();
        diagnostics.error(
                location,
                annotation + " payload " + name + " is a Kotlin data class without @JvmRecord: the default JSON"
                        + " serializer can't build it, because its constructor has no parameter names Jackson can"
                        + " read, so every record fails to decode.\n\n"
                        + "Suggested fixes:\n"
                        + "1. Annotate " + name + " with @JvmRecord (a data class with val parameters)\n"
                        + "2. Or set serializer = YourSerializer::class on " + annotation
                        + " if a serializer of your own handles it");
        return false;
    }

    private static boolean isDefault(TypeRef serializer) {
        return serializer.erasedName().replace('$', '.').equals(DEFAULT_SERIALIZER);
    }
}
