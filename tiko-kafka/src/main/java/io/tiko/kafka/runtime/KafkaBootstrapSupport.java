package io.tiko.kafka.runtime;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.EventCallback;
import io.tiko.kafka.KafkaConfig;
import io.tiko.kafka.KafkaEgressError;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.NamedKafkaSerializer;
import io.tiko.kafka.client.ApacheKafkaConsumerClient;
import io.tiko.kafka.client.ApacheKafkaProducerClient;
import io.tiko.kafka.client.KafkaConsumerClient;
import io.tiko.kafka.client.KafkaProducerClient;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.BiFunction;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;

/**
 * Runtime helper shared by every generated {@code KafkaTransportBootstrap}. Owns the
 * lifetime of consumer runners and the singleton producer client; resolves serializers
 * via {@code ServiceLoader<NamedKafkaSerializer>}.
 *
 * <p>The producer/consumer factories are injectable so unit tests can swap in
 * {@link io.tiko.kafka.test.FakeKafkaBroker}.
 */
public final class KafkaBootstrapSupport {

    private final Container container;
    private final List<GeneratedSourceDescriptor> sources;
    private final List<GeneratedSinkDescriptor> sinks;

    private final BiFunction<KafkaConfig, String, KafkaConsumerClient> consumerFactory;
    private final java.util.function.Function<KafkaConfig, KafkaProducerClient> producerFactory;

    private final List<KafkaConsumerRunner> runners = new ArrayList<>();
    private KafkaProducerClient producer;

    public KafkaBootstrapSupport(
            Container container, List<GeneratedSourceDescriptor> sources, List<GeneratedSinkDescriptor> sinks) {
        this(container, sources, sinks, ApacheKafkaConsumerClient::new, ApacheKafkaProducerClient::new);
    }

    /** Test-only constructor accepting custom client factories (e.g., {@code FakeKafkaBroker}). */
    public KafkaBootstrapSupport(
            Container container,
            List<GeneratedSourceDescriptor> sources,
            List<GeneratedSinkDescriptor> sinks,
            BiFunction<KafkaConfig, String, KafkaConsumerClient> consumerFactory,
            java.util.function.Function<KafkaConfig, KafkaProducerClient> producerFactory) {
        this.container = container;
        this.sources = sources;
        this.sinks = sinks;
        this.consumerFactory = consumerFactory;
        this.producerFactory = producerFactory;
    }

    public void start() {
        KafkaConfig config = container.get(KafkaConfig.class);
        EventBus eventBus = container.getEventBus();
        ErrorHandler errorHandler = resolveErrorHandler(container);
        Map<String, KafkaSerializer> named = loadNamedSerializers();

        // Outbound — subscribe one callback per @KafkaSink.
        if (producer == null && !sinks.isEmpty()) {
            producer = producerFactory.apply(config);
        }
        for (GeneratedSinkDescriptor sink : sinks) {
            KafkaSerializer serializer = resolveSerializer(sink.serializerClass(), config, named);
            eventBus.subscribe(asEventType(sink.eventType()), wrapSinkCallback(sink, serializer, errorHandler));
        }

        // Inbound — one runner per source.
        for (GeneratedSourceDescriptor source : sources) {
            String group = source.consumerGroup().isEmpty() ? config.consumerGroup() : source.consumerGroup();
            KafkaSerializer serializer = resolveSerializer(source.serializerClass(), config, named);
            KafkaConsumerClient client = consumerFactory.apply(config, group);
            KafkaConsumerRunner runner =
                    new ThreadPerTopicRunner(source, client, container, eventBus, errorHandler, serializer, config);
            runners.add(runner);
            runner.start();
        }
    }

    public void shutdown() {
        for (KafkaConsumerRunner r : runners) {
            try {
                r.stop();
            } catch (Exception ignored) {
                /* best-effort */
            }
        }
        runners.clear();
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception ignored) {
                /* best-effort */
            }
            producer = null;
        }
    }

    // --- helpers ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <T> EventCallback<T> wrapSinkCallback(
            GeneratedSinkDescriptor sink, KafkaSerializer serializer, ErrorHandler errorHandler) {
        return (T event) -> {
            try {
                Object payload = sink.dispatcher().dispatch(container, event);
                byte[] bytes = serializer.serialize(payload);
                String key = sink.partitionKey().isEmpty() ? null : resolvePartitionKey(payload, sink.partitionKey());
                producer.send(new ProducerRecord<>(sink.topic(), null, key, bytes, new RecordHeaders()));
            } catch (Exception ex) {
                errorHandler.onError(new KafkaEgressError(sink.topic(), event, ex));
            }
        };
    }

    private static String resolvePartitionKey(Object payload, String accessor) {
        try {
            Method m = payload.getClass().getMethod(accessor);
            Object v = m.invoke(payload);
            return v == null ? null : v.toString();
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException("partitionKey '" + accessor + "' could not be resolved at runtime", e);
        }
    }

    private static Map<String, KafkaSerializer> loadNamedSerializers() {
        Map<String, KafkaSerializer> result = new HashMap<>();
        for (NamedKafkaSerializer named : ServiceLoader.load(NamedKafkaSerializer.class)) {
            result.put(named.name(), named.serializer());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static KafkaSerializer resolveSerializer(
            Class<? extends KafkaSerializer> declared, KafkaConfig config, Map<String, KafkaSerializer> named) {
        if (declared != KafkaSerializer.Default.class) {
            try {
                return declared.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(
                        "@KafkaSource/@KafkaSink serializer "
                                + declared.getName()
                                + " could not be instantiated. It must have a public no-arg constructor.",
                        e);
            }
        }
        KafkaSerializer impl = named.get(config.serializer());
        if (impl == null) {
            throw new RuntimeException("tiko.kafka.serializer = '"
                    + config.serializer()
                    + "' but no NamedKafkaSerializer with that name was found via ServiceLoader. "
                    + "Known names: "
                    + named.keySet());
        }
        return impl;
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> asEventType(Class<?> eventType) {
        return (Class<T>) eventType;
    }

    /**
     * The {@link Container} interface does not expose the {@link ErrorHandler}; tiko-kafka
     * does not need to redesign tiko-api for the MVP. We pull it from the container via a
     * package-private accessor on the runtime if available, otherwise fall back to a
     * default that logs to {@code java.util.logging}.
     */
    private static ErrorHandler resolveErrorHandler(Container container) {
        try {
            Method m = container.getClass().getMethod("getErrorHandler");
            Object eh = m.invoke(container);
            if (eh instanceof ErrorHandler typed) return typed;
        } catch (ReflectiveOperationException ignored) {
            /* fall through */
        }
        return ctx -> java.util.logging.Logger.getLogger("io.tiko.kafka")
                .log(java.util.logging.Level.WARNING, ctx.getClass().getSimpleName() + ": " + ctx.cause(), ctx.cause());
    }
}
