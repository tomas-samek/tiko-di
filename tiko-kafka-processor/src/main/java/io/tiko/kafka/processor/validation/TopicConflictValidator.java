package io.tiko.kafka.processor.validation;

import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import io.tiko.processor.core.Diagnostics;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import java.util.ArrayList;
import java.util.List;

/**
 * Rejects two pairs of bridges that compile and run on the fake broker, but cannot work against
 * a real one:
 * <ul>
 *   <li>two {@code @KafkaSource} bridges on one topic in one consumer group (#750): a broker gives
 *       each partition to one consumer of a group, so each bridge would see a part of the
 *       records, while the fake delivers every record to both;</li>
 *   <li>a {@code @KafkaSource} and a {@code @KafkaSink} on one topic where the sink takes the
 *       event type the source returns (#756): every record read is published locally, picked up
 *       by the sink and sent to the topic again, without end.</li>
 * </ul>
 *
 * <p>Both are checked within one module, on what the annotations state. A {@code consumerGroup}
 * that equals {@code tiko.kafka.consumer-group} only at run time is not seen here.
 */
public final class TopicConflictValidator {

    private TopicConflictValidator() {}

    /**
     * @param knownSources the sources of earlier rounds, already checked against each other
     * @param knownSinks the sinks of earlier rounds
     * @param roundSources this round's sources
     * @param roundSinks this round's sinks
     */
    public static boolean validate(
            Diagnostics diagnostics,
            List<KafkaSourceDescriptor> knownSources,
            List<KafkaSinkDescriptor> knownSinks,
            List<KafkaSourceDescriptor> roundSources,
            List<KafkaSinkDescriptor> roundSinks) {
        boolean ok = sameGroup(diagnostics, knownSources, roundSources);
        // Every pair with a bridge of this round in it, once.
        for (KafkaSinkDescriptor sink : roundSinks) {
            ok &= loops(diagnostics, concat(knownSources, roundSources), sink);
        }
        for (KafkaSinkDescriptor sink : knownSinks) {
            ok &= loops(diagnostics, roundSources, sink);
        }
        return ok;
    }

    private static boolean sameGroup(
            Diagnostics diagnostics, List<KafkaSourceDescriptor> known, List<KafkaSourceDescriptor> round) {
        boolean ok = true;
        var seen = new ArrayList<>(known);
        for (KafkaSourceDescriptor source : round) {
            for (KafkaSourceDescriptor earlier : seen) {
                if (earlier.topic().equals(source.topic())
                        && earlier.consumerGroup().equals(source.consumerGroup())) {
                    diagnostics.error(source.method().location(), sameGroupMessage(source, earlier));
                    ok = false;
                    break;
                }
            }
            seen.add(source);
        }
        return ok;
    }

    private static String sameGroupMessage(KafkaSourceDescriptor source, KafkaSourceDescriptor earlier) {
        var group = source.consumerGroup().isEmpty()
                ? "the default group, tiko.kafka.consumer-group"
                : "group '" + source.consumerGroup() + "'";
        return "@KafkaSource " + name(source.enclosingClass(), source.method()) + " reads topic '" + source.topic()
                + "' in the same consumer group as " + name(earlier.enclosingClass(), earlier.method()) + " (" + group
                + "): a broker gives each partition to one consumer of a group, so each bridge would get only a "
                + "part of the records.\n\nSuggested fixes:\n"
                + "1. Give one of them its own group, @KafkaSource(topic = \"" + source.topic()
                + "\", consumerGroup = \"...\"): then both get every record.\n"
                + "2. Keep one bridge and handle its event in two @EventHandler methods.";
    }

    private static boolean loops(
            Diagnostics diagnostics, List<KafkaSourceDescriptor> sources, KafkaSinkDescriptor sink) {
        if (sink.eventType() == null) {
            return true; // no parameter: BridgeMethodShapeValidator reports it
        }
        for (KafkaSourceDescriptor source : sources) {
            if (source.topic().equals(sink.topic())
                    && source.producedEventType()
                            .erasedName()
                            .equals(sink.eventType().erasedName())) {
                diagnostics.error(sink.method().location(), loopMessage(source, sink));
                return false;
            }
        }
        return true;
    }

    private static String loopMessage(KafkaSourceDescriptor source, KafkaSinkDescriptor sink) {
        var event = sink.eventType().displayName();
        return "@KafkaSink " + name(sink.enclosingClass(), sink.method()) + " sends " + event + " to topic '"
                + sink.topic() + "', and @KafkaSource " + name(source.enclosingClass(), source.method())
                + " publishes " + event + " read from the same topic: every record read would be sent to the topic "
                + "again, without end.\n\nSuggested fixes:\n"
                + "1. Keep the source in the service that reacts to the topic and the sink in the service that "
                + "produces the event; they are two sides of one topic, not one service.\n"
                + "2. To read a topic this service also writes, give the sink an event type the source does not "
                + "return.";
    }

    private static String name(ClassDecl owner, MethodDecl method) {
        return "'" + owner.className().simpleName() + "." + method.name() + "'";
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        var all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
