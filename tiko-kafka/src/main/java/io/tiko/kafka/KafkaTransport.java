package io.tiko.kafka;

import io.tiko.TransportBootstrap;
import io.tiko.kafka.runtime.GeneratedSinkDescriptor;
import io.tiko.kafka.runtime.GeneratedSourceDescriptor;
import java.util.List;

/**
 * Substitution handle implemented by the generated {@code KafkaTransportBootstrap}. Serves two
 * purposes: it is the class key for
 * {@code TikoOptions.builder().replaceTransport(KafkaTransport.class, ...)} (a test
 * affordance in the {@code override(...)} family), and it exposes the compile-time
 * {@code @KafkaSource} / {@code @KafkaSink} descriptors so a replacement transport — chiefly
 * {@link io.tiko.kafka.test.FakeKafkaTransport} — can reuse the generated wiring instead of
 * rebuilding it by hand.
 */
public interface KafkaTransport extends TransportBootstrap {

    /** The generated inbound bridge descriptors, one per {@code @KafkaSource} method. */
    List<GeneratedSourceDescriptor> sources();

    /** The generated outbound bridge descriptors, one per {@code @KafkaSink} method. */
    List<GeneratedSinkDescriptor> sinks();
}
