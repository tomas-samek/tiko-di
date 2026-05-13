package io.tiko.kafka.processor.validation;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import java.util.List;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;

/**
 * Validates that every Kafka bridge component is {@code @Component(scope = Scope.SINGLETON)}.
 * Kafka consumer threads run outside any request/event scope; resolving a non-singleton
 * bridge component would fail at runtime.
 */
public final class SingletonBridgeValidator {

    private SingletonBridgeValidator() {}

    public static boolean validate(
            Messager messager, List<KafkaSourceDescriptor> sources, List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (KafkaSourceDescriptor s : sources) {
            if (!isSingleton(s.enclosingClass())) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSource bridge component must be declared @Component(scope = Scope.SINGLETON). "
                                + "Kafka consumer threads run outside any request/event scope.",
                        s.method());
                ok = false;
            }
        }
        for (KafkaSinkDescriptor s : sinks) {
            if (!isSingleton(s.enclosingClass())) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSink bridge component must be declared @Component(scope = Scope.SINGLETON).",
                        s.method());
                ok = false;
            }
        }
        return ok;
    }

    private static boolean isSingleton(javax.lang.model.element.TypeElement type) {
        Component c = type.getAnnotation(Component.class);
        return c != null && c.scope() == Scope.SINGLETON;
    }
}
