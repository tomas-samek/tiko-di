package io.tiko.kafka.processor.validation;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import io.tiko.processor.core.Diagnostics;
import io.tiko.processor.core.model.ClassDecl;
import java.util.List;

/**
 * Validates that every Kafka bridge component is {@code @Component(scope = Scope.SINGLETON)}.
 * Kafka consumer threads run outside any unit of work (EVENT scope); resolving a non-singleton
 * bridge component would fail at runtime.
 */
public final class SingletonBridgeValidator {

    private static final String COMPONENT = Component.class.getCanonicalName();

    private SingletonBridgeValidator() {}

    public static boolean validate(
            Diagnostics diagnostics, List<KafkaSourceDescriptor> sources, List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (KafkaSourceDescriptor s : sources) {
            if (!isSingleton(s.enclosingClass())) {
                diagnostics.error(
                        s.method().location(),
                        "@KafkaSource bridge component must be declared @Component(scope = Scope.SINGLETON). "
                                + "Kafka consumer threads run outside any unit of work (EVENT scope).");
                ok = false;
            }
        }
        for (KafkaSinkDescriptor s : sinks) {
            if (!isSingleton(s.enclosingClass())) {
                diagnostics.error(
                        s.method().location(),
                        "@KafkaSink bridge component must be declared @Component(scope = Scope.SINGLETON).");
                ok = false;
            }
        }
        return ok;
    }

    /** {@code scope} reads with its declared default, so a bare {@code @Component} is not a singleton. */
    private static boolean isSingleton(ClassDecl type) {
        return type.annotation(COMPONENT)
                .map(component -> component.enumName("scope"))
                .filter(Scope.SINGLETON.name()::equals)
                .isPresent();
    }
}
