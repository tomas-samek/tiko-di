package io.tiko.kafka.processor.validation;

import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import java.util.List;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Validates that {@code @KafkaSink(partitionKey = "name")} resolves to a zero-arg public
 * method (typically a record component accessor) on the bridge's return type.
 */
public final class PartitionKeyValidator {

    private PartitionKeyValidator() {}

    public static boolean validate(ProcessingEnvironment env, Messager messager, List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (KafkaSinkDescriptor s : sinks) {
            if (s.partitionKey().isEmpty()) continue;
            TypeMirror returnType = s.producedPayloadType();
            if (!(returnType instanceof DeclaredType dt)) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSink(partitionKey = \""
                                + s.partitionKey()
                                + "\") cannot be applied to a non-declared return type.",
                        s.method());
                ok = false;
                continue;
            }
            TypeElement returnElement = (TypeElement) dt.asElement();
            boolean found = false;
            for (Element member : env.getElementUtils().getAllMembers(returnElement)) {
                if (member.getKind() != ElementKind.METHOD) continue;
                ExecutableElement m = (ExecutableElement) member;
                if (m.getParameters().isEmpty() && m.getSimpleName().contentEquals(s.partitionKey())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSink partitionKey '"
                                + s.partitionKey()
                                + "' does not resolve to a zero-arg "
                                + "method on "
                                + returnElement.getQualifiedName()
                                + ".",
                        s.method());
                ok = false;
            }
        }
        return ok;
    }
}
