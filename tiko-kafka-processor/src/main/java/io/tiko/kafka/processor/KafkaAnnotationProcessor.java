package io.tiko.kafka.processor;

import com.google.auto.service.AutoService;
import io.tiko.annotations.EventTrigger;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.annotations.KafkaSink;
import io.tiko.kafka.annotations.KafkaSource;
import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import io.tiko.kafka.processor.validation.BridgeMethodShapeValidator;
import io.tiko.kafka.processor.validation.RequiredSiblingValidator;
import io.tiko.kafka.processor.validation.SingletonBridgeValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;

/**
 * Main entry point for the Kafka annotation processor. Independent of
 * {@code TikoAnnotationProcessor}: both register via {@code @AutoService} and both run
 * on the user's annotation-processor path.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Discover all {@code @KafkaSource} and {@code @KafkaSink} methods, build descriptors.</li>
 *   <li>Run validations (see {@link KafkaSourceValidator}, {@link KafkaSinkValidator}).</li>
 *   <li>If no errors: emit {@code io.tiko.generated.KafkaTransportBootstrap} + the
 *       {@code META-INF/services/io.tiko.TransportBootstrap} entry.</li>
 * </ol>
 */
@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public final class KafkaAnnotationProcessor extends AbstractProcessor {

    private boolean done;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(KafkaSource.class.getCanonicalName(), KafkaSink.class.getCanonicalName());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (done || roundEnv.processingOver()) return false;

        List<KafkaSourceDescriptor> sources = new ArrayList<>();
        for (Element e : roundEnv.getElementsAnnotatedWith(KafkaSource.class)) {
            if (e instanceof ExecutableElement m) sources.add(buildSourceDescriptor(m));
        }

        List<KafkaSinkDescriptor> sinks = new ArrayList<>();
        for (Element e : roundEnv.getElementsAnnotatedWith(KafkaSink.class)) {
            if (e instanceof ExecutableElement m) sinks.add(buildSinkDescriptor(m));
        }

        if (!sources.isEmpty() || !sinks.isEmpty()) {
            boolean ok = true;
            ok &= SingletonBridgeValidator.validate(processingEnv.getMessager(), sources, sinks);
            ok &= RequiredSiblingValidator.validate(processingEnv.getMessager(), sources, sinks);
            ok &= BridgeMethodShapeValidator.validate(processingEnv.getMessager(), sources, sinks);
            if (!ok) return false;
        }

        done = true;
        return false;
    }

    private KafkaSourceDescriptor buildSourceDescriptor(ExecutableElement method) {
        KafkaSource ann = method.getAnnotation(KafkaSource.class);
        EventTrigger trigger = method.getAnnotation(EventTrigger.class);

        TypeElement enclosing = (TypeElement) method.getEnclosingElement();
        List<? extends VariableElement> params = method.getParameters();
        TypeMirror payload = params.isEmpty() ? null : params.get(0).asType();
        boolean wantsContext =
                params.size() >= 2 && params.get(1).asType().toString().equals("io.tiko.kafka.KafkaContext");

        return new KafkaSourceDescriptor(
                enclosing,
                method,
                ann.topic(),
                ann.consumerGroup(),
                readClassValue(method, KafkaSource.class, "serializer", KafkaSerializer.Default.class),
                trigger == null ? "" : trigger.eventName(),
                payload,
                method.getReturnType(),
                wantsContext);
    }

    private KafkaSinkDescriptor buildSinkDescriptor(ExecutableElement method) {
        KafkaSink ann = method.getAnnotation(KafkaSink.class);

        TypeElement enclosing = (TypeElement) method.getEnclosingElement();
        List<? extends VariableElement> params = method.getParameters();
        TypeMirror eventType = params.isEmpty() ? null : params.get(0).asType();

        return new KafkaSinkDescriptor(
                enclosing,
                method,
                ann.topic(),
                ann.partitionKey(),
                readClassValue(method, KafkaSink.class, "serializer", KafkaSerializer.Default.class),
                eventType,
                method.getReturnType());
    }

    /**
     * Reads a {@code Class<?>} annotation value via {@link MirroredTypeException}, the only
     * reliable way to get a {@code TypeMirror} for an annotation's class-literal parameter
     * during annotation processing.
     */
    private TypeMirror readClassValue(
            ExecutableElement method, Class<?> annotation, String memberName, Class<?> defaultClass) {
        for (AnnotationMirror am : method.getAnnotationMirrors()) {
            if (!am.getAnnotationType().toString().equals(annotation.getCanonicalName())) continue;
            for (var entry : am.getElementValues().entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals(memberName)) {
                    AnnotationValue v = entry.getValue();
                    return (TypeMirror) v.getValue();
                }
            }
        }
        // No explicit value — return the default class's TypeMirror.
        return processingEnv
                .getElementUtils()
                .getTypeElement(defaultClass.getCanonicalName())
                .asType();
    }
}
