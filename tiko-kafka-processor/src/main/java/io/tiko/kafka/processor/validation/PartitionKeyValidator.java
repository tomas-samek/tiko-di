package io.tiko.kafka.processor.validation;

import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import java.util.List;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Validates that {@code @KafkaSink(partitionKey = "name")} resolves to a zero-arg, public,
 * non-{@code void} method (typically a record component accessor) on the bridge's return type —
 * the shape the generated {@code KafkaTransportBootstrap} key extractor calls. A non-public or
 * {@code void} accessor is reported on the sink method rather than left to surface as a raw javac
 * error inside generated code (#418).
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
            ExecutableElement accessor = null;
            for (Element member : env.getElementUtils().getAllMembers(returnElement)) {
                if (member.getKind() != ElementKind.METHOD) continue;
                ExecutableElement m = (ExecutableElement) member;
                if (m.getParameters().isEmpty() && m.getSimpleName().contentEquals(s.partitionKey())) {
                    accessor = m;
                    break;
                }
            }
            if (accessor == null) {
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
            } else if (accessor.getReturnType().getKind() == TypeKind.VOID) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSink partitionKey '"
                                + s.partitionKey()
                                + "' resolves to "
                                + returnElement.getQualifiedName()
                                + "."
                                + s.partitionKey()
                                + "(), which returns void; a partition-key accessor must return a value the "
                                + "generated bootstrap can turn into a key. Point partitionKey at a non-void "
                                + "zero-arg accessor (e.g. a record component).",
                        s.method());
                ok = false;
            } else if (!accessor.getModifiers().contains(Modifier.PUBLIC)) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSink partitionKey '"
                                + s.partitionKey()
                                + "' resolves to "
                                + returnElement.getQualifiedName()
                                + "."
                                + s.partitionKey()
                                + "(), which is not public; the generated bootstrap calls it from another "
                                + "package. Make "
                                + s.partitionKey()
                                + "() public (record components are public accessors).",
                        s.method());
                ok = false;
            }
        }
        return ok;
    }
}
