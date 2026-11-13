package io.tiko.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method of a {@link TikoTest} class that adds to the options its container boots with,
 * such as a config source or a replaced transport:
 *
 * <pre>{@code
 * @TikoTest
 * class PaymentFlowTest {
 *     private final FakeKafkaBroker broker = new FakeKafkaBroker();
 *
 *     @TikoTestOptions
 *     void options(TikoOptions.Builder options) {
 *         options.configSource(ConfigSources.classpath("application.yaml"))
 *                 .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker));
 *     }
 * }
 * }</pre>
 *
 * <p>The method returns {@code void} and takes one {@link io.tiko.runtime.TikoOptions.Builder}. It
 * runs before each boot. An instance method sees the test's fields and needs the test instance, so
 * with {@link TikoTest.Lifecycle#PER_CLASS} and JUnit's default instance per method it must be
 * {@code static}. Methods of superclasses, and of the classes enclosing a {@code @Nested} class,
 * run before the class's own.
 *
 * <p>{@link TikoTest} owns four settings and rejects a method that sets one: the event bus
 * decorator (the {@link RecordingEventBus}), the event executor, test wiring (always on) and the
 * handler for threads outliving close (the leaked-thread failure).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TikoTestOptions {}
