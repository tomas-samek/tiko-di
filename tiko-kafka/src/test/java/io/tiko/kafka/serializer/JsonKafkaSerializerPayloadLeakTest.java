package io.tiko.kafka.serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A record that fails to deserialize is reported without its payload (SEC-4, #494). The default
 * error handler logs the exception with its stack trace, so neither the message nor any cause in
 * the chain may quote a value; the target type, field path and position stay.
 */
class JsonKafkaSerializerPayloadLeakTest {

    record Payment(String id, int amount) {}

    private static final String SECRET = "SSN-123-45-6789";

    static Stream<Arguments> rejectedPayloads() {
        return Stream.of(
                Arguments.of(
                        "string where an int is declared", "{\"id\":\"p1\",\"amount\":\"" + SECRET + "\"}", "amount"),
                Arguments.of("unquoted token", "{\"id\":\"p1\",\"amount\":" + SECRET.replace("-", "") + "}", null),
                Arguments.of("truncated document", "{\"id\":\"" + SECRET + "\",\"amount\":", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedPayloads")
    void failureNamesTypeAndFieldButNotTheValue(String name, String payload, String field) {
        var failure = catchThrowable(
                () -> new JsonKafkaSerializer().deserialize(payload.getBytes(StandardCharsets.UTF_8), Payment.class));

        var logged = new StringWriter();
        failure.printStackTrace(new PrintWriter(logged));
        assertThat(logged.toString())
                .as("message and every cause the log would print")
                .contains("Payment")
                .doesNotContain(SECRET)
                .doesNotContain(SECRET.replace("-", ""));
        if (field != null) assertThat(failure.getMessage()).contains(field);
    }
}
