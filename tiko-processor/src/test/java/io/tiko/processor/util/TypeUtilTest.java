package io.tiko.processor.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link TypeUtil} against a real {@code javax.lang.model} via Google's
 * compile-testing harness. The probe processor runs in a compile round, captures the
 * Elements/Types it sees, builds a {@link TypeUtil}, and stashes results into static
 * fields that the test inspects post-compile.
 */
class TypeUtilTest {

    @Test
    void exercisesTypeUtilSurfaceViaCompileTestingProcessor() {
        ProbeProcessor.reset();

        JavaFileObject src = JavaFileObjects.forSourceLines(
                "demo.Sample",
                "package demo;",
                "import io.tiko.Picker;",
                "import io.tiko.Provider;",
                "import java.io.Closeable;",
                "import java.util.List;",
                "@io.tiko.annotations.Component(scope = io.tiko.Scope.SINGLETON)",
                "public class Sample implements Runnable, AutoCloseable {",
                "    Sample(Provider<String> p, Picker<String> q, List<String> r) {}",
                "    public void run() {}",
                "    public void close() {}",
                "}",
                "");

        Compilation c = Compiler.javac().withProcessors(new ProbeProcessor()).compile(src);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        assertThat(ProbeProcessor.simpleName).isEqualTo("Sample");
        assertThat(ProbeProcessor.qualifiedName).isEqualTo("demo.Sample");
        assertThat(ProbeProcessor.packageName).isEqualTo("demo");
        assertThat(ProbeProcessor.binaryName).isEqualTo("demo.Sample");

        // Provider<T>: detection + unwrap
        assertThat(ProbeProcessor.providerDetected).isTrue();
        assertThat(ProbeProcessor.providerArgQualifiedName).isEqualTo("java.lang.String");

        // Picker<T>: detection + unwrap
        assertThat(ProbeProcessor.pickerDetected).isTrue();
        assertThat(ProbeProcessor.pickerArgQualifiedName).isEqualTo("java.lang.String");

        // Non-provider type: detection false, unwrap empty
        assertThat(ProbeProcessor.nonProviderDetected).isFalse();
        assertThat(ProbeProcessor.nonProviderUnwrap).isEmpty();

        // First interface skips AutoCloseable / Closeable
        assertThat(ProbeProcessor.firstInterfaceQualifiedName).isEqualTo("java.lang.Runnable");

        // isAssignable / isSameType
        assertThat(ProbeProcessor.assignable).isTrue();
        assertThat(ProbeProcessor.sameType).isTrue();

        // getTypeElement: present + absent
        assertThat(ProbeProcessor.knownTypeFound).isTrue();
        assertThat(ProbeProcessor.unknownTypeFound).isFalse();

        // implementsInterface
        assertThat(ProbeProcessor.implementsRunnable).isTrue();
        assertThat(ProbeProcessor.implementsUnknown).isFalse();

        // isAssignableTo
        assertThat(ProbeProcessor.assignableToRunnable).isTrue();
        assertThat(ProbeProcessor.assignableToUnknown).isFalse();

        // isConcreteClass
        assertThat(ProbeProcessor.concreteClass).isTrue();

        // erasure + asTypeElement on a primitive returns empty
        assertThat(ProbeProcessor.erasureNonNull).isTrue();
        assertThat(ProbeProcessor.primitiveAsTypeElementEmpty).isTrue();
    }

    @SupportedAnnotationTypes("io.tiko.annotations.Component")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    public static class ProbeProcessor extends AbstractProcessor {

        static String simpleName;
        static String qualifiedName;
        static String packageName;
        static String binaryName;
        static boolean providerDetected;
        static String providerArgQualifiedName;
        static boolean pickerDetected;
        static String pickerArgQualifiedName;
        static boolean nonProviderDetected;
        static Optional<TypeMirror> nonProviderUnwrap;
        static String firstInterfaceQualifiedName;
        static boolean assignable;
        static boolean sameType;
        static boolean knownTypeFound;
        static boolean unknownTypeFound;
        static boolean implementsRunnable;
        static boolean implementsUnknown;
        static boolean assignableToRunnable;
        static boolean assignableToUnknown;
        static boolean concreteClass;
        static boolean erasureNonNull;
        static boolean primitiveAsTypeElementEmpty;

        static void reset() {
            simpleName = null;
            qualifiedName = null;
            packageName = null;
            binaryName = null;
            providerDetected = false;
            providerArgQualifiedName = null;
            pickerDetected = false;
            pickerArgQualifiedName = null;
            nonProviderDetected = false;
            nonProviderUnwrap = null;
            firstInterfaceQualifiedName = null;
            assignable = false;
            sameType = false;
            knownTypeFound = false;
            unknownTypeFound = false;
            implementsRunnable = false;
            implementsUnknown = false;
            assignableToRunnable = false;
            assignableToUnknown = false;
            concreteClass = false;
            erasureNonNull = false;
            primitiveAsTypeElementEmpty = false;
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) return false;

            TypeUtil util = new TypeUtil(processingEnv.getElementUtils(), processingEnv.getTypeUtils());

            TypeElement sample = processingEnv.getElementUtils().getTypeElement("demo.Sample");
            if (sample == null) return false;

            TypeMirror sampleMirror = sample.asType();
            simpleName = util.getSimpleName(sampleMirror);
            qualifiedName = util.getQualifiedName(sampleMirror);
            packageName = util.getPackageName(sample);
            binaryName = util.getBinaryName(sample);

            // Constructor parameters: Provider<String>, Picker<String>, List<String>
            var ctorParams = sample.getEnclosedElements().stream()
                    .filter(e -> e.getKind() == javax.lang.model.element.ElementKind.CONSTRUCTOR)
                    .map(e -> (javax.lang.model.element.ExecutableElement) e)
                    .findFirst()
                    .orElseThrow()
                    .getParameters();
            TypeMirror provider = ((VariableElement) ctorParams.get(0)).asType();
            TypeMirror picker = ((VariableElement) ctorParams.get(1)).asType();
            TypeMirror list = ((VariableElement) ctorParams.get(2)).asType();

            providerDetected = util.isProvider(provider);
            providerArgQualifiedName =
                    util.unwrapProvider(provider).map(util::getQualifiedName).orElse(null);
            pickerDetected = util.isPicker(picker);
            pickerArgQualifiedName =
                    util.unwrapPicker(picker).map(util::getQualifiedName).orElse(null);
            nonProviderDetected = util.isProvider(list);
            nonProviderUnwrap = util.unwrapProvider(list);

            firstInterfaceQualifiedName =
                    util.getFirstInterface(sample).map(util::getQualifiedName).orElse(null);

            assignable = util.isAssignable(sampleMirror, sampleMirror);
            sameType = util.isSameType(sampleMirror, sampleMirror);

            knownTypeFound = util.getTypeElement("java.lang.String").isPresent();
            unknownTypeFound = util.getTypeElement("does.not.Exist").isPresent();

            implementsRunnable = util.implementsInterface(sample, "java.lang.Runnable");
            implementsUnknown = util.implementsInterface(sample, "does.not.Exist");

            assignableToRunnable = util.isAssignableTo(sampleMirror, "java.lang.Runnable");
            assignableToUnknown = util.isAssignableTo(sampleMirror, "does.not.Exist");

            concreteClass = util.isConcreteClass(sample);

            erasureNonNull = util.erasure(sampleMirror) != null;

            // Primitive types are not declared types — asTypeElement returns empty.
            TypeMirror primitiveInt = processingEnv.getTypeUtils().getPrimitiveType(javax.lang.model.type.TypeKind.INT);
            primitiveAsTypeElementEmpty = util.asTypeElement(primitiveInt).isEmpty();

            return false;
        }
    }
}
