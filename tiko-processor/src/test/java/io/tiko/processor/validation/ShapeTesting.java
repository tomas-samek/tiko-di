package io.tiko.processor.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;

/**
 * What the validation "shapes" tests share: each compiles small sources and expects either a clean
 * compile, or one located error with a suggested fix and nothing failing in generated code.
 */
final class ShapeTesting {

    private static final Pattern PACKAGE = Pattern.compile("package ([\\w.]+);");
    private static final Pattern TYPE_NAME = Pattern.compile("(?:class|interface|record) (\\w+)");

    private ShapeTesting() {}

    /**
     * Compiles each source under the package it declares (default {@code demo}) and its first type's
     * name, with {@code imports} between the package line and the source.
     */
    static Compilation compile(String imports, List<String> sources) {
        var files = new ArrayList<JavaFileObject>();
        for (String source : sources) {
            var declared = PACKAGE.matcher(source);
            boolean declaresPackage = declared.find();
            String packageName = declaresPackage ? declared.group(1) : "demo";
            String body = declaresPackage ? source.substring(declared.end()) : source;
            var type = TYPE_NAME.matcher(body);
            assertThat(type.find()).as("source declares a type").isTrue();
            files.add(JavaFileObjects.forSourceString(
                    packageName + "." + type.group(1), "package " + packageName + ";\n" + imports + body));
        }
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(files);
    }

    static List<String> errorsOf(Compilation compilation) {
        return compilation.errors().stream()
                .map(error -> error.getMessage(Locale.ROOT))
                .toList();
    }

    /**
     * The compile failed with one error that carries every fragment and a suggested fix; the
     * processor did not crash and no error is in generated code.
     */
    static void assertLocatedError(Compilation compilation, List<String> fragments) {
        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(errorsOf(compilation))
                .as("no processor crash")
                .noneMatch(message -> message.contains("Tiko DI processing failed"));
        assertThat(errorsOf(compilation))
                .anySatisfy(message -> assertThat(message).contains(fragments).contains("Suggested fixes:"));
        assertThat(compilation.errors())
                .as("no error in generated code")
                .noneMatch(error ->
                        error.getSource() != null && error.getSource().getName().contains("io/tiko/generated"));
    }

    /** A SINGLETON component {@code Listener} whose body is {@code handler}. */
    static String listener(String handler) {
        return """
                @Component(scope = Scope.SINGLETON)
                public class Listener {
                    %s
                }
                """.formatted(handler);
    }
}
