package io.tiko.processor.validation;

import static io.tiko.processor.CompileTesting.compile;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import io.tiko.processor.CompileTesting;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #812: a {@code Picker<T>} lists every implementation of {@code T}, so it does not ask which of
 * several unnamed ones is meant. Only an injection point that takes {@code T} itself does.
 */
class PickerAmbiguityTest {

    private static final String IMPORTS =
            "import io.tiko.Picker; import io.tiko.Provider; import io.tiko.Scope; import io.tiko.annotations.*;";

    private static JavaFileObject source(String name, String... lines) {
        return CompileTesting.source(IMPORTS, name, lines);
    }

    private static final JavaFileObject CHANNEL = source("Channel", "public interface Channel {}");
    private static final JavaFileObject EMAIL =
            source("Email", "@Component(scope = Scope.SINGLETON)", "public class Email implements Channel {}");
    private static final JavaFileObject SMS =
            source("Sms", "@Component(scope = Scope.SINGLETON)", "public class Sms implements Channel {}");
    private static final JavaFileObject CATALOG = consumer("Catalog", "Picker<Channel> channels");

    /** A SINGLETON {@code name} that injects {@code parameter}. */
    private static JavaFileObject consumer(String name, String parameter) {
        return source(
                name,
                "@Component(scope = Scope.SINGLETON)",
                "public class " + name + " {",
                "  @Inject public " + name + "(" + parameter + ") {}",
                "}");
    }

    static Stream<Arguments> pickerConsumers() {
        return Stream.of(
                Arguments.of("constructor parameter", CATALOG),
                Arguments.of(
                        "@Produces parameter",
                        source(
                                "Wiring",
                                "@Component(scope = Scope.SINGLETON)",
                                "public class Wiring {",
                                "  @Produces(scope = Scope.SINGLETON)",
                                "  public StringBuilder names(Picker<Channel> channels) { return new StringBuilder(); }",
                                "}")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pickerConsumers")
    void twoUnnamedImplementationsBehindAPickerCompile(String name, JavaFileObject consumer) {
        Compilation c = compile(CHANNEL, EMAIL, SMS, consumer);

        CompilationSubject.assertThat(c).succeeded();
    }

    static Stream<Arguments> directConsumers() {
        return Stream.of(
                Arguments.of("the type itself", "Channel channel"),
                Arguments.of("a Provider of the type", "Provider<Channel> channel"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("directConsumers")
    void unqualifiedInjectionNextToAPickerIsStillAmbiguous(String name, String parameter) {
        Compilation c = compile(CHANNEL, EMAIL, SMS, CATALOG, consumer("Sender", parameter));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c)
                .hadErrorContaining(
                        "Multiple unnamed providers for type demo.Channel: Email (@Component), Sms (@Component)");
    }

    @Test
    void pickedInjectionNextToAPickerCompiles() {
        Compilation c = compile(CHANNEL, EMAIL, SMS, CATALOG, consumer("Sender", "@Pick(Email.class) Channel channel"));

        CompilationSubject.assertThat(c).succeeded();
    }
}
